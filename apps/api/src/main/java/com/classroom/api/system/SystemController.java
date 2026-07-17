package com.classroom.api.system;

import com.classroom.api.config.ServerConfig;
import com.classroom.api.http.HttpHandlerFunction;
import com.classroom.api.http.HttpResponses;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SystemController {
  private final ServerConfig config;

  public SystemController(ServerConfig config) {
    this.config = config;
  }

  public HttpHandlerFunction rootHandler() {
    return exchange -> HttpResponses.json(exchange, 200, Map.of(
        "name", "live-classroom-quiz-system",
        "service", "api",
        "runtime", "java",
        "health", "/api/health"
    ));
  }

  public HttpHandlerFunction healthHandler() {
    return exchange -> HttpResponses.json(exchange, 200, healthPayload());
  }

  private Map<String, Object> healthPayload() {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", "ok");
    payload.put("service", "classroom-api");
    payload.put("runtime", "java");
    payload.put("port", config.port());
    payload.put("timestamp", Instant.now().toString());
    payload.put("dependencies", Map.of(
        "databaseUrl", config.databaseUrl(),
        "redisUrl", config.redisUrl()
    ));
    payload.put("realtime", Map.of(
        "transport", "websocket",
        "path", config.endpoints().websocketPath()
    ));
    return payload;
  }
}
