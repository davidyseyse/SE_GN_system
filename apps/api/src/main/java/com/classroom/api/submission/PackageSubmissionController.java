package com.classroom.api.submission;

import com.classroom.api.auth.AuthContext;
import com.classroom.api.auth.AuthorizationGuard;
import com.classroom.api.buzz.BuzzDeadlineService;
import com.classroom.api.contract.QuestionState;
import com.classroom.api.contract.Role;
import com.classroom.api.data.model.ClassroomEntity;
import com.classroom.api.data.model.QuestionEntity;
import com.classroom.api.data.model.QuestionItemEntity;
import com.classroom.api.data.model.SheetConfigEntity;
import com.classroom.api.data.model.SubmissionEntity;
import com.classroom.api.data.model.UserEntity;
import com.classroom.api.data.repository.BuzzRoundRepository;
import com.classroom.api.data.repository.ClassroomRepository;
import com.classroom.api.data.repository.QuestionItemRepository;
import com.classroom.api.data.repository.QuestionRepository;
import com.classroom.api.data.repository.SheetConfigRepository;
import com.classroom.api.data.repository.SubmissionRepository;
import com.classroom.api.data.repository.UserRepository;
import com.classroom.api.http.HttpExchangeAdapter;
import com.classroom.api.http.HttpHandlerFunction;
import com.classroom.api.http.HttpResponses;
import com.classroom.api.question.QuestionPackageService;
import com.classroom.api.realtime.RealtimeEventBroker;
import com.classroom.api.sheets.GoogleSheetsSyncService;
import com.classroom.api.sheets.SheetAnswerFormatter;
import com.classroom.api.util.JsonParser;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public final class PackageSubmissionController {
  private final AuthorizationGuard authorizationGuard;
  private final ClassroomRepository classroomRepository;
  private final QuestionRepository questionRepository;
  private final QuestionItemRepository itemRepository;
  private final SubmissionRepository submissionRepository;
  private final UserRepository userRepository;
  private final BuzzRoundRepository roundRepository;
  private final SheetConfigRepository sheetConfigRepository;
  private final QuestionPackageService packageService;
  private final GoogleSheetsSyncService sheetsSyncService;
  private final RealtimeEventBroker eventBroker;
  private final BuzzDeadlineService buzzDeadlineService;

  public PackageSubmissionController(
      AuthorizationGuard authorizationGuard,
      ClassroomRepository classroomRepository,
      QuestionRepository questionRepository,
      QuestionItemRepository itemRepository,
      SubmissionRepository submissionRepository,
      UserRepository userRepository,
      BuzzRoundRepository roundRepository,
      SheetConfigRepository sheetConfigRepository,
      QuestionPackageService packageService,
      GoogleSheetsSyncService sheetsSyncService,
      RealtimeEventBroker eventBroker,
      BuzzDeadlineService buzzDeadlineService
  ) {
    this.authorizationGuard = authorizationGuard;
    this.classroomRepository = classroomRepository;
    this.questionRepository = questionRepository;
    this.itemRepository = itemRepository;
    this.submissionRepository = submissionRepository;
    this.userRepository = userRepository;
    this.roundRepository = roundRepository;
    this.sheetConfigRepository = sheetConfigRepository;
    this.packageService = packageService;
    this.sheetsSyncService = sheetsSyncService;
    this.eventBroker = eventBroker;
    this.buzzDeadlineService = buzzDeadlineService;
  }

  public HttpHandlerFunction submitHandler() {
    return exchange -> {
      AuthContext auth = authorizationGuard.requireRoles(exchange, Role.STUDENT);
      Map<String, Object> request = parseBody(exchange);
      ClassroomEntity classroom = requireClassroom(stringValue(request, "classroomCode"));
      UUID packageId = UUID.fromString(stringValue(request, "packageId"));
      QuestionEntity question = questionRepository.findById(classroom.id(), packageId);
      if (question == null) {
        if (itemRepository.isItemId(packageId)) {
          throw new IllegalArgumentException("Subquestions cannot be submitted independently");
        }
        throw new IllegalArgumentException("Question package not found");
      }
      QuestionState state = QuestionState.valueOf(question.state());
      if (state != QuestionState.BUZZ_OPEN && state != QuestionState.ANSWER_OPEN) {
        throw new IllegalArgumentException("Question package is not accepting answers");
      }
      UserEntity user = userRepository.findByExternalId(auth.subject());
      if (user == null) throw new IllegalArgumentException("User not found");
      int round = roundRepository.current(packageId);
      boolean winnerSubmission = state == QuestionState.BUZZ_OPEN;
      if (!winnerSubmission && submissionRepository.hasSubmission(packageId, user.id(), round)) {
        throw new IllegalArgumentException("Duplicate package submission is not allowed");
      }

      List<QuestionItemEntity> items = itemRepository.listByQuestionId(packageId);
      Map<UUID, String> answers = parseAnswers(request.get("answers"));
      Set<UUID> expectedIds = items.stream().map(QuestionItemEntity::id).collect(Collectors.toSet());
      if (!answers.keySet().equals(expectedIds)) {
        throw new IllegalArgumentException("Answers must contain exactly one entry for every subquestion");
      }
      String answerJson = toJson(answers);
      SubmissionEntity submission = new SubmissionEntity(
          UUID.randomUUID(), classroom.id(), packageId, user.id(), 1, answerJson,
          "RECEIVED", null, null, "PENDING", Instant.now(), null);
      if (winnerSubmission) {
        buzzDeadlineService.saveWinnerSubmission(submission, round);
      } else {
        submissionRepository.save(submission, round);
      }

      String syncStatus = "NOT_REQUIRED";
      UUID configId = packageService.findSheetConfigId(packageId);
      if (configId != null) {
        SheetConfigEntity config = sheetConfigRepository.findById(configId);
        if (config != null) {
          Map<String, String> values = new LinkedHashMap<>();
          for (QuestionItemEntity item : items) {
            if (item.targetCell() != null) {
              values.put(
                  item.targetCell(),
                  SheetAnswerFormatter.format(answers.get(item.id()), user.displayName()));
            }
          }
          try {
            sheetsSyncService.updateCells(config.sheetId(), values);
            syncStatus = "SYNCED";
            submissionRepository.updateSheetSync(submission.id(), syncStatus, null);
          } catch (RuntimeException exception) {
            syncStatus = "RETRYABLE";
            submissionRepository.updateSheetSync(submission.id(), syncStatus, exception.getMessage());
          }
        }
      }

      eventBroker.publish(classroom.classroomCode(), "SUBMISSION_CREATED", Map.of(
          "submissionId", submission.id().toString(),
          "packageId", packageId.toString(),
          "questionId", packageId.toString(),
          "roundId", Integer.toString(round),
          "userId", user.id().toString(),
          "externalUserId", user.externalId(),
          "displayName", user.displayName(),
          "status", submission.submissionStatus(),
          "reviewStatus", submission.reviewStatus()
      ));
      HttpResponses.json(exchange, 200, Map.of(
          "submissionId", submission.id().toString(),
          "syncStatus", syncStatus
      ));
    };
  }

  public HttpHandlerFunction retrySyncHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, Object> request = parseBody(exchange);
      UUID submissionId = UUID.fromString(stringValue(request, "submissionId"));
      SubmissionEntity submission = submissionRepository.findById(submissionId);
      if (submission == null) throw new IllegalArgumentException("Submission not found");
      List<QuestionItemEntity> items = itemRepository.listByQuestionId(submission.questionId());
      Object parsed = JsonParser.parse(submission.submittedAnswer());
      if (!(parsed instanceof Map<?, ?> values)) {
        throw new IllegalArgumentException("Submission does not contain package answers");
      }
      UUID configId = packageService.findSheetConfigId(submission.questionId());
      SheetConfigEntity config = configId == null ? null : sheetConfigRepository.findById(configId);
      if (config == null) throw new IllegalArgumentException("Package sheet configuration is missing");
      UserEntity user = userRepository.findById(submission.userId());
      Map<String, String> valuesByCell = new LinkedHashMap<>();
      for (QuestionItemEntity item : items) {
        Object answer = values.get(item.id().toString());
        if (answer == null || item.targetCell() == null) {
          throw new IllegalArgumentException("Submission or package binding is incomplete");
        }
        valuesByCell.put(item.targetCell(), SheetAnswerFormatter.format(
            String.valueOf(answer),
            user == null ? submission.userId().toString() : user.displayName()));
      }
      try {
        sheetsSyncService.updateCells(config.sheetId(), valuesByCell);
        submissionRepository.updateSheetSync(submissionId, "SYNCED", null);
        HttpResponses.json(exchange, 200, Map.of(
            "submissionId", submissionId.toString(),
            "syncStatus", "SYNCED"
        ));
      } catch (RuntimeException exception) {
        submissionRepository.updateSheetSync(submissionId, "RETRYABLE", exception.getMessage());
        throw exception;
      }
    };
  }

  private static Map<UUID, String> parseAnswers(Object raw) {
    if (!(raw instanceof List<?> list)) throw new IllegalArgumentException("answers must be an array");
    Map<UUID, String> answers = new LinkedHashMap<>();
    for (int index = 0; index < list.size(); index++) {
      if (!(list.get(index) instanceof Map<?, ?> answer)) {
        throw new IllegalArgumentException("answers[" + index + "] must be an object");
      }
      UUID itemId = UUID.fromString(String.valueOf(answer.get("itemId")));
      Object rawAnswer = answer.get("answer");
      if (answers.put(itemId, rawAnswer == null ? "" : String.valueOf(rawAnswer)) != null) {
        throw new IllegalArgumentException("Duplicate answer itemId: " + itemId);
      }
    }
    return answers;
  }

  private static String toJson(Map<UUID, String> answers) {
    StringBuilder json = new StringBuilder("{");
    for (Map.Entry<UUID, String> answer : answers.entrySet()) {
      if (json.length() > 1) json.append(',');
      json.append('"').append(answer.getKey()).append("\":\"")
          .append(escape(answer.getValue())).append('"');
    }
    return json.append('}').toString();
  }

  private ClassroomEntity requireClassroom(String code) {
    ClassroomEntity classroom = classroomRepository.findByCode(code.toUpperCase());
    if (classroom == null) throw new IllegalArgumentException("Classroom not found");
    return classroom;
  }

  private static Map<String, Object> parseBody(HttpExchangeAdapter exchange) throws IOException {
    return JsonParser.parseObject(exchange.readRequestBody());
  }

  private static String stringValue(Map<String, Object> values, String key) {
    Object value = values.get(key);
    if (value == null || value.toString().isBlank()) throw new IllegalArgumentException(key + " is required");
    return value.toString().trim();
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r");
  }
}
