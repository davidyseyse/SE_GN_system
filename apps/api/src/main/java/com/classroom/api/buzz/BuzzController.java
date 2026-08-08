package com.classroom.api.buzz;

import com.classroom.api.auth.AuthContext;
import com.classroom.api.auth.AuthorizationGuard;
import com.classroom.api.contract.QuestionState;
import com.classroom.api.contract.Role;
import com.classroom.api.data.model.BuzzRecordEntity;
import com.classroom.api.data.model.ClassroomEntity;
import com.classroom.api.data.model.QuestionEntity;
import com.classroom.api.data.model.UserEntity;
import com.classroom.api.data.repository.BuzzRepository;
import com.classroom.api.data.repository.BuzzRoundRepository;
import com.classroom.api.data.repository.ClassroomRepository;
import com.classroom.api.data.repository.QuestionRepository;
import com.classroom.api.data.repository.QuestionItemRepository;
import com.classroom.api.data.repository.UserRepository;
import com.classroom.api.http.HttpHandlerFunction;
import com.classroom.api.http.HttpResponses;
import com.classroom.api.redis.RedisBuzzLockService;
import com.classroom.api.realtime.RealtimeEventBroker;
import com.classroom.api.question.QuestionLifecycleService;
import com.classroom.api.score.ClassroomScoringPolicy;
import com.classroom.api.data.model.ScoreRecordEntity;
import com.classroom.api.data.repository.ScoreRepository;
import com.classroom.api.util.JsonMapParser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Comparator;

public final class BuzzController {
  private final AuthorizationGuard authorizationGuard;
  private final ClassroomRepository classroomRepository;
  private final QuestionRepository questionRepository;
  private final UserRepository userRepository;
  private final BuzzRepository buzzRepository;
  private final ScoreRepository scoreRepository;
  private final RedisBuzzLockService redisBuzzLockService;
  private final BuzzDeadlineService buzzDeadlineService;
  private final RealtimeEventBroker eventBroker;
  private final BuzzRoundRepository buzzRoundRepository;
  private final QuestionLifecycleService lifecycleService;
  private final QuestionItemRepository questionItemRepository;

  public BuzzController(
      AuthorizationGuard authorizationGuard,
      ClassroomRepository classroomRepository,
      QuestionRepository questionRepository,
      UserRepository userRepository,
      BuzzRepository buzzRepository,
      ScoreRepository scoreRepository,
      RedisBuzzLockService redisBuzzLockService,
      BuzzDeadlineService buzzDeadlineService,
      RealtimeEventBroker eventBroker,
      BuzzRoundRepository buzzRoundRepository,
      QuestionLifecycleService lifecycleService,
      QuestionItemRepository questionItemRepository
  ) {
    this.authorizationGuard = authorizationGuard;
    this.classroomRepository = classroomRepository;
    this.questionRepository = questionRepository;
    this.userRepository = userRepository;
    this.buzzRepository = buzzRepository;
    this.scoreRepository = scoreRepository;
    this.redisBuzzLockService = redisBuzzLockService;
    this.buzzDeadlineService = buzzDeadlineService;
    this.eventBroker = eventBroker;
    this.buzzRoundRepository = buzzRoundRepository;
    this.lifecycleService = lifecycleService;
    this.questionItemRepository = questionItemRepository;
  }

  public HttpHandlerFunction buzzHandler() {
    return exchange -> {
      AuthContext authContext = authorizationGuard.requireRoles(exchange, Role.STUDENT);
      Map<String, String> request = JsonMapParser.parseObject(exchange.readRequestBody());
      ClassroomEntity classroom = requireClassroom(request.get("classroomCode"));
      QuestionEntity question = requireQuestion(classroom.id(), request.get("questionId"));
      if (QuestionState.valueOf(question.state()) != QuestionState.BUZZ_OPEN) {
        throw new IllegalArgumentException("Buzz window is closed");
      }

      UserEntity user = requireUser(authContext.subject());
      int roundNumber = buzzRoundRepository.current(question.id());
      if (roundNumber <= 0) {
        throw new IllegalArgumentException("No active buzz round");
      }
      String requestId = request.getOrDefault("requestId", UUID.randomUUID().toString());
      String roundKey = question.id() + ":" + roundNumber;
      buzzDeadlineService.expireOverdueWinner(question.id(), roundNumber);
      long sequence = redisBuzzLockService.nextSequence("buzz:sequence:" + roundKey);
      UUID recordId = UUID.randomUUID();
      boolean winner = buzzRepository.findActiveWinner(question.id(), roundNumber) == null
          && tryWinnerLock("buzz:winner:" + roundKey, recordId, question.answerTimeLimitSeconds());
      Instant now = Instant.now();
      Instant answerDeadlineAt = winner && isPositive(question.answerTimeLimitSeconds())
          ? now.plusSeconds(question.answerTimeLimitSeconds()) : null;

      BuzzRecordEntity record = new BuzzRecordEntity(
          recordId,
          classroom.id(),
          question.id(),
          user.id(),
          requestId,
          sequence,
          now,
          winner ? "WINNER" : "LATE",
          winner,
          answerDeadlineAt
      );
      try {
        buzzRepository.save(record, roundNumber);
      } catch (RuntimeException exception) {
        if (winner) {
          redisBuzzLockService.deleteIfValue("buzz:winner:" + roundKey, recordId.toString());
        }
        throw exception;
      }

      if (winner) {
        ClassroomScoringPolicy scoringPolicy = ClassroomScoringPolicy.fromSettings(classroom.participationSettings());
        if (scoringPolicy.buzzBonus() != 0) {
          int runningTotal = scoreRepository.currentTotal(classroom.id(), user.id()) + scoringPolicy.buzzBonus();
          scoreRepository.save(new ScoreRecordEntity(
              UUID.randomUUID(),
              classroom.id(),
              user.id(),
              question.id(),
              "BUZZ_BONUS",
              scoringPolicy.buzzBonus(),
              runningTotal,
              now
          ));
        }
      }

      eventBroker.publish(classroom.classroomCode(), winner ? "BUZZ_RESULT" : "BUZZ_ATTEMPT_RECORDED", Map.of(
          "packageId", question.id().toString(),
          "questionId", question.id().toString(),
          "roundId", Integer.toString(roundNumber),
          "userId", user.id().toString(),
          "externalUserId", user.externalId(),
          "displayName", user.displayName(),
          "requestId", requestId,
          "receiptOrder", sequence,
          "successful", winner,
          "answerDeadlineAt", answerDeadlineAt == null ? "" : answerDeadlineAt.toString()
      ));

      HttpResponses.json(exchange, 200, Map.of(
          "accepted", winner,
          "requestId", requestId,
          "receiptOrder", sequence,
          "outcomeStatus", record.outcomeStatus(),
          "answerDeadlineAt", answerDeadlineAt == null ? "" : answerDeadlineAt.toString()
      ));
    };
  }

  public HttpHandlerFunction historyHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      ClassroomEntity classroom = requireClassroom(exchange.queryParam("classroomCode"));
      QuestionEntity question = requireQuestion(classroom.id(), exchange.queryParam("questionId"));
      List<BuzzRecordEntity> records = buzzRepository.findByQuestionId(question.id());
      HttpResponses.json(exchange, 200, Map.of(
          "classroomCode", classroom.classroomCode(),
          "questionId", question.id().toString(),
          "history", records.stream().map(record -> Map.of(
              "id", record.id().toString(),
              "userId", record.userId().toString(),
              "requestId", record.requestId(),
              "receiptOrder", record.receiptOrder(),
              "receivedAt", record.receivedAt().toString(),
              "outcomeStatus", record.outcomeStatus(),
              "successful", record.successful()
          )).toList()
      ));
    };
  }

  public HttpHandlerFunction cancelHandler() {
    return exchange -> {
      AuthContext authContext = authorizationGuard.requireRoles(exchange, Role.STUDENT);
      Map<String, String> request = JsonMapParser.parseObject(exchange.readRequestBody());
      ClassroomEntity classroom = requireClassroom(request.get("classroomCode"));
      QuestionEntity question = requireQuestion(classroom.id(), request.get("questionId"));
      if (QuestionState.valueOf(question.state()) != QuestionState.BUZZ_OPEN) {
        throw new IllegalArgumentException("Buzz window is closed");
      }
      UserEntity user = requireUser(authContext.subject());
      int roundNumber = buzzRoundRepository.current(question.id());

      buzzDeadlineService.cancelWinner(question.id(), roundNumber, user.id());
      eventBroker.publish(classroom.classroomCode(), "BUZZ_CANCELLED", Map.of(
          "questionId", question.id().toString(),
          "roundId", Integer.toString(roundNumber),
          "userId", user.id().toString(),
          "externalUserId", user.externalId(),
          "displayName", user.displayName()
      ));

      HttpResponses.json(exchange, 200, Map.of(
          "questionId", question.id().toString(),
          "status", "OPEN"
      ));
    };
  }

  public HttpHandlerFunction resetHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, String> request = JsonMapParser.parseObject(exchange.readRequestBody());
      ClassroomEntity classroom = requireClassroom(request.get("classroomCode"));
      String questionIdValue = request.getOrDefault("questionId", "").trim();

      if (!questionIdValue.isEmpty()) {
        QuestionEntity question = requireQuestion(classroom.id(), questionIdValue);
        lifecycleService.reset(classroom, question);
      } else {
        for (QuestionEntity question : questionRepository.listByClassroom(classroom.id())) {
          lifecycleService.reset(classroom, question);
        }
      }

      HttpResponses.json(exchange, 200, Map.of(
          "classroomCode", classroom.classroomCode(),
          "status", "RESET"
      ));
    };
  }

  private ClassroomEntity requireClassroom(String classroomCode) {
    if (classroomCode == null || classroomCode.isBlank()) {
      throw new IllegalArgumentException("classroomCode is required");
    }
    ClassroomEntity classroom = classroomRepository.findByCode(classroomCode);
    if (classroom == null) {
      throw new IllegalArgumentException("Classroom not found");
    }
    return classroom;
  }

  private QuestionEntity requireQuestion(UUID classroomId, String questionIdValue) {
    if (questionIdValue == null || questionIdValue.isBlank()) {
      throw new IllegalArgumentException("questionId is required");
    }
    UUID targetId = UUID.fromString(questionIdValue);
    QuestionEntity question = questionRepository.findById(classroomId, targetId);
    if (question == null) {
      if (questionItemRepository.isItemId(targetId)) {
        throw new IllegalArgumentException("Subquestions cannot be buzzed independently");
      }
      throw new IllegalArgumentException("Question not found");
    }
    return question;
  }

  private UserEntity requireUser(String externalId) {
    UserEntity user = userRepository.findByExternalId(externalId);
    if (user == null) {
      throw new IllegalArgumentException("User not found");
    }
    return user;
  }

  private boolean tryWinnerLock(String key, UUID recordId, Integer answerTimeLimitSeconds) {
    if (!isPositive(answerTimeLimitSeconds)) {
      return redisBuzzLockService.tryLock(key, recordId.toString());
    }
    long ttl = (long) answerTimeLimitSeconds + BuzzDeadlineService.REDIS_CLEANUP_GRACE_SECONDS;
    return redisBuzzLockService.tryLock(key, recordId.toString(), (int) Math.min(Integer.MAX_VALUE, ttl));
  }

  private static boolean isPositive(Integer value) {
    return value != null && value > 0;
  }

}
