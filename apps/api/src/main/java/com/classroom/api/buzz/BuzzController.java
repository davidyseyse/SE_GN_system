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
import com.classroom.api.data.repository.ClassroomRepository;
import com.classroom.api.data.repository.QuestionRepository;
import com.classroom.api.data.repository.UserRepository;
import com.classroom.api.http.HttpHandlerFunction;
import com.classroom.api.http.HttpResponses;
import com.classroom.api.redis.RedisBuzzLockService;
import com.classroom.api.realtime.RealtimeEventBroker;
import com.classroom.api.score.ClassroomScoringPolicy;
import com.classroom.api.data.model.ScoreRecordEntity;
import com.classroom.api.data.repository.ScoreRepository;
import com.classroom.api.util.JsonMapParser;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class BuzzController {
  private final AuthorizationGuard authorizationGuard;
  private final ClassroomRepository classroomRepository;
  private final QuestionRepository questionRepository;
  private final UserRepository userRepository;
  private final BuzzRepository buzzRepository;
  private final ScoreRepository scoreRepository;
  private final RedisBuzzLockService redisBuzzLockService;
  private final RealtimeEventBroker eventBroker;

  public BuzzController(
      AuthorizationGuard authorizationGuard,
      ClassroomRepository classroomRepository,
      QuestionRepository questionRepository,
      UserRepository userRepository,
      BuzzRepository buzzRepository,
      ScoreRepository scoreRepository,
      RedisBuzzLockService redisBuzzLockService,
      RealtimeEventBroker eventBroker
  ) {
    this.authorizationGuard = authorizationGuard;
    this.classroomRepository = classroomRepository;
    this.questionRepository = questionRepository;
    this.userRepository = userRepository;
    this.buzzRepository = buzzRepository;
    this.scoreRepository = scoreRepository;
    this.redisBuzzLockService = redisBuzzLockService;
    this.eventBroker = eventBroker;
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
      String requestId = request.getOrDefault("requestId", UUID.randomUUID().toString());
      long sequence = redisBuzzLockService.nextSequence("buzz:sequence:" + question.id());
      boolean winner = redisBuzzLockService.tryLock("buzz:winner:" + question.id(), requestId, 300);
      Instant now = Instant.now();

      BuzzRecordEntity record = new BuzzRecordEntity(
          UUID.randomUUID(),
          classroom.id(),
          question.id(),
          user.id(),
          requestId,
          sequence,
          now,
          winner ? "WINNER" : "LATE",
          winner
      );
      buzzRepository.save(record);

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
          "questionId", question.id().toString(),
          "userId", user.id().toString(),
          "requestId", requestId,
          "receiptOrder", sequence,
          "successful", winner
      ));

      HttpResponses.json(exchange, 200, Map.of(
          "accepted", winner,
          "requestId", requestId,
          "receiptOrder", sequence,
          "outcomeStatus", record.outcomeStatus()
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
    QuestionEntity question = questionRepository.findById(classroomId, UUID.fromString(questionIdValue));
    if (question == null) {
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
}
