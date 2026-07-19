package com.classroom.api.auth;

import com.classroom.api.config.ServerConfig;
import com.classroom.api.contract.Role;
import com.classroom.api.http.HttpExchangeAdapter;
import com.classroom.api.http.HttpHandlerFunction;
import com.classroom.api.http.HttpResponses;
import com.classroom.api.util.JsonMapParser;
import java.io.IOException;
import java.util.Map;

public final class AuthController {
  private final AuthTokenService authTokenService;
  private final ServerConfig config;

  public AuthController(ServerConfig config) {
    this.config = config;
    this.authTokenService = new AuthTokenService(config);
  }

  public HttpHandlerFunction issueTokenHandler() {
    return exchange -> {
      AuthRequest request = parseRequest(exchange);
      String token = authTokenService.issueToken(request.userId(), request.role(), request.displayName());
      AuthResponse response = new AuthResponse(
          token,
          "Bearer",
          config.jwtTtlSeconds(),
          request.userId(),
          request.displayName(),
          request.role()
      );
      HttpResponses.json(exchange, 200, Map.of(
          "accessToken", response.accessToken(),
          "tokenType", response.tokenType(),
          "expiresInSeconds", response.expiresInSeconds(),
          "user", Map.of(
              "id", response.userId(),
              "displayName", response.displayName(),
              "role", response.role().name()
          )
      ));
    };
  }

  public HttpHandlerFunction adminLoginHandler() {
    return exchange -> {
      Map<String, String> values = JsonMapParser.parseObject(exchange.readRequestBody());
      String username = values.getOrDefault("username", "").trim();
      String password = values.getOrDefault("password", "").trim();
      if (username.isEmpty() || password.isEmpty()) {
        throw new IllegalArgumentException("username and password are required");
      }
      if (!config.adminUsername().equals(username) || !config.adminPassword().equals(password)) {
        throw new SecurityException("帳號或密碼錯誤");
      }

      String token = authTokenService.issueToken("teacher-" + username, Role.TEACHER, username);
      HttpResponses.json(exchange, 200, Map.of(
          "accessToken", token,
          "tokenType", "Bearer",
          "expiresInSeconds", config.jwtTtlSeconds(),
          "user", Map.of(
              "id", "teacher-" + username,
              "displayName", username,
              "role", Role.TEACHER.name()
          )
      ));
    };
  }

  public HttpHandlerFunction studentLoginHandler() {
    return exchange -> {
      Map<String, String> values = JsonMapParser.parseObject(exchange.readRequestBody());
      String studentId = values.getOrDefault("studentId", "").trim();
      String groupNumberValue = values.getOrDefault("groupNumber", "").trim();
      if (studentId.isEmpty() || groupNumberValue.isEmpty()) {
        throw new IllegalArgumentException("studentId and groupNumber are required");
      }

      int groupNumber;
      try {
        groupNumber = Integer.parseInt(groupNumberValue);
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException("groupNumber must be a positive integer");
      }
      if (groupNumber <= 0) {
        throw new IllegalArgumentException("groupNumber must be a positive integer");
      }

      String displayName = studentId + " / 第" + groupNumber + "組";
      String token = authTokenService.issueToken(studentId, Role.STUDENT, displayName);
      HttpResponses.json(exchange, 200, Map.of(
          "accessToken", token,
          "tokenType", "Bearer",
          "expiresInSeconds", config.jwtTtlSeconds(),
          "user", Map.of(
              "id", studentId,
              "displayName", displayName,
              "role", Role.STUDENT.name()
          ),
          "studentId", studentId,
          "groupNumber", groupNumber
      ));
    };
  }

  public AuthTokenService authTokenService() {
    return authTokenService;
  }

  private static AuthRequest parseRequest(HttpExchangeAdapter exchange) throws IOException {
    String body = exchange.readRequestBody();
    Map<String, String> values = JsonMapParser.parseObject(body);

    String userId = values.getOrDefault("userId", "").trim();
    String displayName = values.getOrDefault("displayName", "").trim();
    String roleValue = values.getOrDefault("role", "").trim().toUpperCase();

    if (userId.isEmpty() || displayName.isEmpty() || roleValue.isEmpty()) {
      throw new IllegalArgumentException("userId, displayName, and role are required");
    }

    return new AuthRequest(userId, displayName, Role.valueOf(roleValue));
  }
}
