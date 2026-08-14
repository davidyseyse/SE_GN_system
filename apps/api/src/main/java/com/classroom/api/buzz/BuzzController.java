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
import com.classroom.api.redis.RedisBuzzSequenceService;
import com.classroom.api.realtime.RealtimeEventBroker;
import com.classroom.api.question.QuestionLifecycleService;
import com.classroom.api.score.ClassroomScoringPolicy;
import com.classroom.api.sheets.SheetAnswerFormatter;
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
  private final RedisBuzzSequenceService redisBuzzSequenceService;
  private final BuzzAttemptService buzzAttemptService;
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
      RedisBuzzSequenceService redisBuzzSequenceService,
      BuzzAttemptService buzzAttemptService,
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
    this.redisBuzzSequenceService = redisBuzzSequenceService;
    this.buzzAttemptService = buzzAttemptService;
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
      if (!classroomRepository.hasMember(classroom.id(), user.id())) {
        throw new SecurityException("User is not a member of this classroom");
      }
      int roundNumber = buzzRoundRepository.current(question.id());
      if (roundNumber <= 0) {
        throw new IllegalArgumentException("No active buzz round");
      }
      String requestId = request.getOrDefault("requestId", UUID.randomUUID().toString());
      String roundKey = question.id() + ":" + roundNumber;
      ClassroomScoringPolicy policy = ClassroomScoringPolicy.fromSettings(classroom.participationSettings());
      Integer groupNumber = SheetAnswerFormatter.extractGroupNumber(user.displayName());
      if (groupNumber == null) {
        throw new IllegalArgumentException("Student group number is required");
      }
      int groupSuccessfulBuzzCount = classroomRepository.globalGroupSuccessfulBuzzCount(groupNumber);
      if (policy.hasReachedSuccessfulBuzzLimit(groupSuccessfulBuzzCount)) {
        HttpResponses.json(exchange, 200, Map.of(
            "accepted", false,
            "successful", false,
            "requestId", requestId,
            "receiptOrder", 0,
            "outcomeStatus", "SUCCESS_LIMIT_REACHED",
            "groupNumber", groupNumber,
            "groupSuccessfulBuzzCount", groupSuccessfulBuzzCount,
            "successfulBuzzLimit", policy.successfulBuzzLimit()
        ));
        return;
      }

      long sequence = redisBuzzSequenceService.nextSequence("buzz:sequence:" + roundKey);
      UUID recordId = UUID.randomUUID();
      Instant now = Instant.now();

      BuzzRecordEntity record = new BuzzRecordEntity(
          recordId,
          classroom.id(),
          question.id(),
          user.id(),
          requestId,
          sequence,
          now,
          "RECORDED",
          false,
          null
      );
      buzzRepository.save(record, roundNumber);

      eventBroker.publish(classroom.classroomCode(), "BUZZ_ATTEMPT_RECORDED", Map.of(
          "packageId", question.id().toString(),
          "questionId", question.id().toString(),
          "roundId", Integer.toString(roundNumber),
          "userId", user.id().toString(),
          "externalUserId", user.externalId(),
          "displayName", user.displayName(),
          "requestId", requestId,
          "receiptOrder", sequence,
          "successful", false
      ));

      HttpResponses.json(exchange, 200, Map.of(
          "accepted", true,
          "successful", false,
          "requestId", requestId,
          "receiptOrder", sequence,
          "outcomeStatus", record.outcomeStatus(),
          "groupNumber", groupNumber,
          "groupSuccessfulBuzzCount", groupSuccessfulBuzzCount,
          "successfulBuzzLimit", policy.successfulBuzzLimit()
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

      buzzAttemptService.cancelAttempt(question.id(), roundNumber, user.id());
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

}
