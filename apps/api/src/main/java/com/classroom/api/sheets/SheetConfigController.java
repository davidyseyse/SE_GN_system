package com.classroom.api.sheets;

import com.classroom.api.auth.AuthorizationGuard;
import com.classroom.api.contract.Role;
import com.classroom.api.data.model.SheetConfigEntity;
import com.classroom.api.data.repository.SheetConfigRepository;
import com.classroom.api.http.HttpExchangeAdapter;
import com.classroom.api.http.HttpHandlerFunction;
import com.classroom.api.http.HttpResponses;
import com.classroom.api.util.JsonMapParser;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class SheetConfigController {
  private final AuthorizationGuard authorizationGuard;
  private final SheetConfigRepository repository;
  private final GoogleSheetsSyncService sheetsService;

  public SheetConfigController(
      AuthorizationGuard authorizationGuard,
      SheetConfigRepository repository,
      GoogleSheetsSyncService sheetsService
  ) {
    this.authorizationGuard = authorizationGuard;
    this.repository = repository;
    this.sheetsService = sheetsService;
  }

  public HttpHandlerFunction createHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, String> request = parseBody(exchange);
      String name = requiredValue(request, "name");
      String sheetId = requiredValue(request, "sheetId");
      Instant now = Instant.now();
      SheetConfigEntity config = new SheetConfigEntity(UUID.randomUUID(), name, sheetId, now, now);
      repository.save(config);
      HttpResponses.json(exchange, 201, Map.of("config", serialize(config)));
    };
  }

  public HttpHandlerFunction listHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      HttpResponses.json(exchange, 200, Map.of(
          "configs", repository.list().stream().map(SheetConfigController::serialize).toList()
      ));
    };
  }

  public HttpHandlerFunction deleteHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, String> request = parseBody(exchange);
      String id = requiredValue(request, "configId");
      repository.delete(UUID.fromString(id));
      HttpResponses.json(exchange, 200, Map.of("deleted", id));
    };
  }

  public HttpHandlerFunction statisticsHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, String> request = parseBody(exchange);
      UUID configId = UUID.fromString(requiredValue(request, "configId"));
      SheetConfigEntity config = repository.findById(configId);
      if (config == null) {
        throw new IllegalArgumentException("Sheet config does not exist");
      }
      SheetGroupStatistics.Result statistics = sheetsService.readGroupStatistics(config.sheetId());
      HttpResponses.json(exchange, 200, Map.of(
          "config", serialize(config),
          "successfulGroupCount", statistics.successfulGroupCount(),
          "totalSuccessfulAnswers", statistics.totalSuccessfulAnswers(),
          "groups", statistics.groups().stream().map(group -> Map.of(
              "groupNumber", group.groupNumber(),
              "successfulAnswerCount", group.successfulAnswerCount()
          )).toList()
      ));
    };
  }

  private static Map<String, Object> serialize(SheetConfigEntity config) {
    return Map.of(
        "id", config.id().toString(),
        "name", config.name(),
        "sheetId", config.sheetId(),
        "createdAt", config.createdAt().toString()
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
}
