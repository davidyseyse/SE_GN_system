package com.classroom.api;

import com.classroom.api.auth.AuthTokenService;
import com.classroom.api.config.ServerConfig;
import com.classroom.api.contract.Role;
import com.classroom.api.util.EnvFileLoader;
import java.util.Map;

public final class SmokeTest {
  private SmokeTest() {
  }

  public static void main(String[] args) {
    Map<String, String> env = EnvFileLoader.load();
    String port = env.getOrDefault("API_PORT", System.getenv().getOrDefault("API_PORT", "8080"));
    ServerConfig config = ServerConfig.load();
    AuthTokenService tokenService = new AuthTokenService(config);
    String token = tokenService.issueToken("smoke-user", Role.TEACHER, "Smoke User");
    tokenService.verify(token);
    System.out.println("Smoke test passed. API_PORT=" + port + ", JWT=" + token.substring(0, Math.min(token.length(), 24)) + "...");
  }
}
