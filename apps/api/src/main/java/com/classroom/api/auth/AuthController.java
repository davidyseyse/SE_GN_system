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
