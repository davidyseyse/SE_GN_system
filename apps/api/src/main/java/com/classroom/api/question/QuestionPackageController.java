package com.classroom.api.question;

import com.classroom.api.auth.AuthContext;
import com.classroom.api.auth.AuthorizationGuard;
import com.classroom.api.contract.Role;
import com.classroom.api.data.model.ClassroomEntity;
import com.classroom.api.data.model.QuestionEntity;
import com.classroom.api.data.model.QuestionItemEntity;
import com.classroom.api.data.model.UserEntity;
import com.classroom.api.data.repository.ClassroomRepository;
import com.classroom.api.data.repository.QuestionItemRepository;
import com.classroom.api.data.repository.QuestionRepository;
import com.classroom.api.data.repository.UserRepository;
import com.classroom.api.http.HttpExchangeAdapter;
import com.classroom.api.http.HttpHandlerFunction;
import com.classroom.api.http.HttpResponses;
import com.classroom.api.util.JsonParser;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class QuestionPackageController {
  private final AuthorizationGuard authorizationGuard;
  private final ClassroomRepository classroomRepository;
  private final UserRepository userRepository;
  private final QuestionRepository questionRepository;
  private final QuestionItemRepository itemRepository;
  private final QuestionPackageService packageService;

  public QuestionPackageController(
      AuthorizationGuard authorizationGuard,
      ClassroomRepository classroomRepository,
      UserRepository userRepository,
      QuestionRepository questionRepository,
      QuestionItemRepository itemRepository,
      QuestionPackageService packageService
  ) {
    this.authorizationGuard = authorizationGuard;
    this.classroomRepository = classroomRepository;
    this.userRepository = userRepository;
    this.questionRepository = questionRepository;
    this.itemRepository = itemRepository;
    this.packageService = packageService;
  }

  public HttpHandlerFunction createHandler() {
    return exchange -> {
      AuthContext auth = authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, Object> request = parseBody(exchange);
      ClassroomEntity classroom = requireClassroom(stringValue(request, "classroomCode"));
      UserEntity actor = requireUser(auth.subject());
      UUID sheetConfigId = UUID.fromString(stringValue(request, "sheetConfigId"));
      List<QuestionPackageService.ItemDraft> items = parseItems(request.get("items"));
      int score = integerValue(request.get("score"), 10, "score");
      Integer limit = request.containsKey("answerTimeLimitSeconds")
          ? integerValue(request.get("answerTimeLimitSeconds"), 30, "answerTimeLimitSeconds")
          : 30;
      QuestionPackageService.CreatedPackage created = packageService.create(
          classroom.id(), actor.id(), stringValue(request, "title"), sheetConfigId,
          items, score, limit);
      HttpResponses.json(exchange, 201, Map.of("package", serialize(
          created.packageEntity(), created.sheetConfigId(), created.items())));
    };
  }

  public HttpHandlerFunction listHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT, Role.STUDENT);
      ClassroomEntity classroom = requireClassroom(requiredQuery(exchange, "classroomCode"));
      List<Map<String, Object>> packages = questionRepository.listByClassroom(classroom.id()).stream()
          .map(question -> serialize(
              question,
              packageService.findSheetConfigId(question.id()),
              itemRepository.listByQuestionId(question.id())))
          .toList();
      HttpResponses.json(exchange, 200, Map.of(
          "classroomId", classroom.id().toString(),
          "packages", packages
      ));
    };
  }

  private static List<QuestionPackageService.ItemDraft> parseItems(Object raw) {
    if (!(raw instanceof List<?> values)) {
      throw new IllegalArgumentException("items must be an array");
    }
    List<QuestionPackageService.ItemDraft> items = new ArrayList<>();
    for (int index = 0; index < values.size(); index++) {
      if (!(values.get(index) instanceof Map<?, ?> item)) {
        throw new IllegalArgumentException("items[" + index + "] must be an object");
      }
      Object prompt = item.get("prompt");
      Object targetCell = item.get("targetCell");
      items.add(new QuestionPackageService.ItemDraft(
          prompt == null ? "" : prompt.toString(),
          targetCell == null ? "" : targetCell.toString()));
    }
    return items;
  }

  private static Map<String, Object> serialize(
      QuestionEntity question,
      UUID sheetConfigId,
      List<QuestionItemEntity> items
  ) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("id", question.id().toString());
    payload.put("packageId", question.id().toString());
    payload.put("questionId", question.id().toString());
    payload.put("classroomId", question.classroomId().toString());
    payload.put("ordinal", question.ordinal());
    payload.put("title", question.prompt());
    payload.put("prompt", question.prompt());
    payload.put("type", question.type());
    payload.put("state", question.state());
    payload.put("score", question.score());
    payload.put("answerTimeLimitSeconds",
        question.answerTimeLimitSeconds() == null ? "" : question.answerTimeLimitSeconds().toString());
    payload.put("sheetConfigId", sheetConfigId == null ? "" : sheetConfigId.toString());
    payload.put("items", items.stream().map(QuestionPackageController::serializeItem).toList());
    return payload;
  }

  private static Map<String, Object> serializeItem(QuestionItemEntity item) {
    return Map.of(
        "id", item.id().toString(),
        "ordinal", item.ordinal(),
        "prompt", item.prompt(),
        "targetCell", item.targetCell() == null ? "" : item.targetCell()
    );
  }

  private ClassroomEntity requireClassroom(String code) {
    ClassroomEntity classroom = classroomRepository.findByCode(code.toUpperCase());
    if (classroom == null) throw new IllegalArgumentException("Classroom not found");
    return classroom;
  }

  private UserEntity requireUser(String externalId) {
    UserEntity user = userRepository.findByExternalId(externalId);
    if (user == null) throw new IllegalArgumentException("User not found");
    return user;
  }

  private static Map<String, Object> parseBody(HttpExchangeAdapter exchange) throws IOException {
    return JsonParser.parseObject(exchange.readRequestBody());
  }

  private static String stringValue(Map<String, Object> values, String key) {
    Object raw = values.get(key);
    String value = raw == null ? "" : raw.toString().trim();
    if (value.isEmpty()) throw new IllegalArgumentException(key + " is required");
    return value;
  }

  private static int integerValue(Object raw, int defaultValue, String key) {
    if (raw == null) return defaultValue;
    if (raw instanceof Number number) return number.intValue();
    try {
      return Integer.parseInt(raw.toString());
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException(key + " must be an integer");
    }
  }

  private static String requiredQuery(HttpExchangeAdapter exchange, String key) {
    String value = exchange.queryParam(key);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is required");
    return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
  }
}
