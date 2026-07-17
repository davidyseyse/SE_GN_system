package com.classroom.api.question;

import com.classroom.api.auth.AuthContext;
import com.classroom.api.auth.AuthorizationGuard;
import com.classroom.api.contract.QuestionState;
import com.classroom.api.contract.Role;
import com.classroom.api.data.model.ClassroomEntity;
import com.classroom.api.data.model.QuestionEntity;
import com.classroom.api.data.model.UserEntity;
import com.classroom.api.data.repository.ClassroomRepository;
import com.classroom.api.data.repository.QuestionRepository;
import com.classroom.api.data.repository.UserRepository;
import com.classroom.api.http.HttpExchangeAdapter;
import com.classroom.api.http.HttpHandlerFunction;
import com.classroom.api.http.HttpResponses;
import com.classroom.api.util.JsonMapParser;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class QuestionController {
  private final QuestionRepository questionRepository;
  private final ClassroomRepository classroomRepository;
  private final UserRepository userRepository;
  private final AuthorizationGuard authorizationGuard;
  private final QuestionStateMachine stateMachine;

  public QuestionController(
      QuestionRepository questionRepository,
      ClassroomRepository classroomRepository,
      UserRepository userRepository,
      AuthorizationGuard authorizationGuard
  ) {
    this.questionRepository = questionRepository;
    this.classroomRepository = classroomRepository;
    this.userRepository = userRepository;
    this.authorizationGuard = authorizationGuard;
    this.stateMachine = new QuestionStateMachine();
  }

  public HttpHandlerFunction createQuestionHandler() {
    return exchange -> {
      AuthContext authContext = authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, String> request = parseBody(exchange);
      ClassroomEntity classroom = requireClassroom(requiredValue(request, "classroomCode"));
      UserEntity actor = requireUser(authContext.subject());
      int ordinal = nextOrdinal(classroom.id());
      Instant now = Instant.now();

      QuestionEntity question = new QuestionEntity(
          UUID.randomUUID(),
          classroom.id(),
          ordinal,
          requiredValue(request, "type"),
          QuestionState.DRAFT.name(),
          requiredValue(request, "prompt"),
          request.getOrDefault("answerKey", "{}"),
          request.getOrDefault("publicationSettings", "{}"),
          parseInteger(request.getOrDefault("score", "0"), "score"),
          parseNullableInteger(request.get("answerTimeLimitSeconds"), "answerTimeLimitSeconds"),
          actor.id(),
          now,
          now,
          null
      );

      questionRepository.save(question);
      HttpResponses.json(exchange, 201, Map.of("question", serialize(question)));
    };
  }

  public HttpHandlerFunction updateQuestionHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, String> request = parseBody(exchange);
      ClassroomEntity classroom = requireClassroom(requiredValue(request, "classroomCode"));
      UUID questionId = UUID.fromString(requiredValue(request, "questionId"));
      QuestionEntity existing = requireQuestion(classroom.id(), questionId);
      Instant now = Instant.now();

      QuestionEntity updated = new QuestionEntity(
          existing.id(),
          existing.classroomId(),
          existing.ordinal(),
          request.getOrDefault("type", existing.type()),
          existing.state(),
          request.getOrDefault("prompt", existing.prompt()),
          request.getOrDefault("answerKey", existing.answerKey()),
          request.getOrDefault("publicationSettings", existing.publicationSettings()),
          request.containsKey("score") ? parseInteger(request.get("score"), "score") : existing.score(),
          request.containsKey("answerTimeLimitSeconds")
              ? parseNullableInteger(request.get("answerTimeLimitSeconds"), "answerTimeLimitSeconds")
              : existing.answerTimeLimitSeconds(),
          existing.createdBy(),
          existing.createdAt(),
          now,
          existing.archivedAt()
      );

      questionRepository.update(updated);
      HttpResponses.json(exchange, 200, Map.of("question", serialize(updated)));
    };
  }

  public HttpHandlerFunction archiveQuestionHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, String> request = parseBody(exchange);
      ClassroomEntity classroom = requireClassroom(requiredValue(request, "classroomCode"));
      UUID questionId = UUID.fromString(requiredValue(request, "questionId"));
      QuestionEntity existing = requireQuestion(classroom.id(), questionId);
      QuestionEntity archived = new QuestionEntity(
          existing.id(),
          existing.classroomId(),
          existing.ordinal(),
          existing.type(),
          QuestionState.CLOSED.name(),
          existing.prompt(),
          existing.answerKey(),
          existing.publicationSettings(),
          existing.score(),
          existing.answerTimeLimitSeconds(),
          existing.createdBy(),
          existing.createdAt(),
          Instant.now(),
          Instant.now()
      );
      questionRepository.update(archived);
      HttpResponses.json(exchange, 200, Map.of("question", serialize(archived)));
    };
  }

  public HttpHandlerFunction reorderQuestionsHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, String> request = parseBody(exchange);
      ClassroomEntity classroom = requireClassroom(requiredValue(request, "classroomCode"));
      UUID questionId = UUID.fromString(requiredValue(request, "questionId"));
      int newOrdinal = parseInteger(requiredValue(request, "newOrdinal"), "newOrdinal");
      questionRepository.reorder(classroom.id(), questionId, newOrdinal);
      HttpResponses.json(exchange, 200, Map.of(
          "classroomId", classroom.id().toString(),
          "questions", questionRepository.listByClassroom(classroom.id()).stream().map(QuestionController::serialize).toList()
      ));
    };
  }

  public HttpHandlerFunction listQuestionsHandler() {
    return exchange -> {
      AuthContext authContext = authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT, Role.STUDENT);
      ClassroomEntity classroom = requireClassroom(requiredQuery(exchange, "classroomCode"));
      List<QuestionEntity> questions = questionRepository.listByClassroom(classroom.id());
      List<Map<String, Object>> payload = questions.stream()
          .filter(question -> authContext.role() != Role.STUDENT || isVisibleToStudent(question))
          .map(question -> authContext.role() == Role.STUDENT ? serializeForStudent(question) : serialize(question))
          .toList();

      HttpResponses.json(exchange, 200, Map.of(
          "classroomId", classroom.id().toString(),
          "questions", payload
      ));
    };
  }

  public HttpHandlerFunction transitionStateHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, String> request = parseBody(exchange);
      ClassroomEntity classroom = requireClassroom(requiredValue(request, "classroomCode"));
      UUID questionId = UUID.fromString(requiredValue(request, "questionId"));
      QuestionState targetState = QuestionState.valueOf(requiredValue(request, "targetState").toUpperCase());
      QuestionEntity existing = requireQuestion(classroom.id(), questionId);
      stateMachine.assertTransitionAllowed(QuestionState.valueOf(existing.state()), targetState);

      QuestionEntity updated = new QuestionEntity(
          existing.id(),
          existing.classroomId(),
          existing.ordinal(),
          existing.type(),
          targetState.name(),
          existing.prompt(),
          existing.answerKey(),
          existing.publicationSettings(),
          existing.score(),
          existing.answerTimeLimitSeconds(),
          existing.createdBy(),
          existing.createdAt(),
          Instant.now(),
          existing.archivedAt()
      );

      questionRepository.update(updated);
      HttpResponses.json(exchange, 200, Map.of(
          "question", serialize(updated),
          "event", Map.of(
              "type", "QUESTION_STATE_CHANGED",
              "questionId", updated.id().toString(),
              "state", updated.state(),
              "occurredAt", updated.updatedAt().toString()
          )
      ));
    };
  }

  private ClassroomEntity requireClassroom(String classroomCode) {
    ClassroomEntity classroom = classroomRepository.findByCode(classroomCode);
    if (classroom == null) {
      throw new IllegalArgumentException("Classroom not found");
    }
    return classroom;
  }

  private QuestionEntity requireQuestion(UUID classroomId, UUID questionId) {
    QuestionEntity question = questionRepository.findById(classroomId, questionId);
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

  private int nextOrdinal(UUID classroomId) {
    return questionRepository.listByClassroom(classroomId).size() + 1;
  }

  private static boolean isVisibleToStudent(QuestionEntity question) {
    return switch (QuestionState.valueOf(question.state())) {
      case ANSWER_OPEN, BUZZ_OPEN, LOCKED, REVIEW, PUBLISHED -> true;
      default -> false;
    };
  }

  private static Map<String, Object> serialize(QuestionEntity question) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", question.id().toString());
    payload.put("classroomId", question.classroomId().toString());
    payload.put("ordinal", question.ordinal());
    payload.put("type", question.type());
    payload.put("state", question.state());
    payload.put("prompt", question.prompt());
    payload.put("answerKey", question.answerKey());
    payload.put("publicationSettings", question.publicationSettings());
    payload.put("score", question.score());
    payload.put("answerTimeLimitSeconds", question.answerTimeLimitSeconds() == null ? "" : question.answerTimeLimitSeconds().toString());
    payload.put("archivedAt", question.archivedAt() == null ? "" : question.archivedAt().toString());
    return payload;
  }

  private static Map<String, Object> serializeForStudent(QuestionEntity question) {
    String publicationSettings = question.publicationSettings();
    boolean showAnswer = QuestionState.PUBLISHED.name().equals(question.state()) || publicationSettings.contains("\"showAnswer\":true");
    return Map.of(
        "id", question.id().toString(),
        "ordinal", question.ordinal(),
        "type", question.type(),
        "state", question.state(),
        "prompt", question.prompt(),
        "answerKey", showAnswer ? question.answerKey() : "",
        "score", question.score()
    );
  }

  private static Map<String, String> parseBody(HttpExchangeAdapter exchange) throws IOException {
    return JsonMapParser.parseObject(exchange.readRequestBody());
  }

  private static String requiredValue(Map<String, String> values, String key) {
    String value = values.getOrDefault(key, "").trim();
    if (value.isEmpty()) {
      throw new IllegalArgumentException(key + " is required");
    }
    return value;
  }

  private static String requiredQuery(HttpExchangeAdapter exchange, String key) {
    String value = exchange.queryParam(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(key + " query parameter is required");
    }
    return value;
  }

  private static int parseInteger(String value, String fieldName) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(fieldName + " must be an integer");
    }
  }

  private static Integer parseNullableInteger(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return parseInteger(value, fieldName);
  }
}
