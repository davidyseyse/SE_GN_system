package com.classroom.api.config;

import com.classroom.api.util.EnvFileLoader;
import com.classroom.api.http.json.JsonWriter;
import java.util.Map;

public record ServerConfig(
    int port,
    int websocketPort,
    String host,
    String corsOrigin,
    String databaseUrl,
    String redisUrl,
    String jwtSecret,
    String jwtIssuer,
    String jwtAudience,
    long jwtTtlSeconds,
    ServiceEndpoints endpoints
) {
  public static ServerConfig load() {
    Map<String, String> fileEnv = EnvFileLoader.load();
    int port = Integer.parseInt(resolve("API_PORT", "8080", fileEnv));
    int websocketPort = Integer.parseInt(resolve("WEBSOCKET_PORT", String.valueOf(port + 1), fileEnv));
    String host = resolve("API_HOST", "0.0.0.0", fileEnv);
    String corsOrigin = resolve("CORS_ORIGIN", "*", fileEnv);
    String databaseUrl = resolve("DATABASE_URL", "postgres://classroom:classroom@localhost:5432/classroom", fileEnv);
    String redisUrl = resolve("REDIS_URL", "redis://localhost:6379", fileEnv);
    String jwtSecret = resolve("JWT_SECRET", "change-me", fileEnv);
    String jwtIssuer = resolve("JWT_ISSUER", "classroom-api", fileEnv);
    String jwtAudience = resolve("JWT_AUDIENCE", "classroom-clients", fileEnv);
    long jwtTtlSeconds = Long.parseLong(resolve("JWT_TTL_SECONDS", "28800", fileEnv));
    String apiBaseUrl = resolve("API_BASE_URL", "http://localhost:" + port, fileEnv);
    String websocketUrl = resolve("WEBSOCKET_URL", "ws://localhost:" + websocketPort + "/ws", fileEnv);
    String websocketPath = resolve("WEBSOCKET_PATH", "/ws", fileEnv);
    String websocketTopicPrefix = resolve("WEBSOCKET_TOPIC_PREFIX", "classroom", fileEnv);
    ServiceEndpoints endpoints = new ServiceEndpoints(apiBaseUrl, websocketUrl, websocketPath, websocketTopicPrefix);
    return new ServerConfig(
        port,
        websocketPort,
        host,
        corsOrigin,
        databaseUrl,
        redisUrl,
        jwtSecret,
        jwtIssuer,
        jwtAudience,
        jwtTtlSeconds,
        endpoints
    );
  }

  public JsonWriter jsonWriter() {
    return new JsonWriter();
  }

  private static String resolve(String key, String fallback, Map<String, String> fileEnv) {
    String systemValue = System.getenv(key);
    if (systemValue != null && !systemValue.isBlank()) {
      return systemValue;
    }

    String fileValue = fileEnv.get(key);
    if (fileValue != null && !fileValue.isBlank()) {
      return fileValue;
    }

    return fallback;
  }
}
