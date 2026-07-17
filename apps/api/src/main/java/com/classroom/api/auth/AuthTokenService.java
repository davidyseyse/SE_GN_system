package com.classroom.api.auth;

import com.classroom.api.config.ServerConfig;
import com.classroom.api.contract.Role;
import com.classroom.api.util.Base64Url;
import com.classroom.api.util.HmacSigner;
import com.classroom.api.util.JsonMapParser;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class AuthTokenService {
  private final ServerConfig config;

  public AuthTokenService(ServerConfig config) {
    this.config = config;
  }

  public String issueToken(String subject, Role role, String displayName) {
    Instant now = Instant.now();
    Map<String, Object> header = Map.of(
        "alg", "HS256",
        "typ", "JWT"
    );
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sub", subject);
    payload.put("role", role.name());
    payload.put("name", displayName);
    payload.put("iss", config.jwtIssuer());
    payload.put("aud", config.jwtAudience());
    payload.put("iat", now.getEpochSecond());
    payload.put("exp", now.plusSeconds(config.jwtTtlSeconds()).getEpochSecond());

    String encodedHeader = Base64Url.encode(config.jsonWriter().toJson(header).getBytes(StandardCharsets.UTF_8));
    String encodedPayload = Base64Url.encode(config.jsonWriter().toJson(payload).getBytes(StandardCharsets.UTF_8));
    String signingInput = encodedHeader + "." + encodedPayload;
    String signature = HmacSigner.sign(signingInput, config.jwtSecret());
    return signingInput + "." + signature;
  }

  public AuthContext verify(String token) {
    String[] parts = token.split("\\.");
    if (parts.length != 3) {
      throw new IllegalArgumentException("Malformed token");
    }

    String signingInput = parts[0] + "." + parts[1];
    String expectedSignature = HmacSigner.sign(signingInput, config.jwtSecret());
    if (!expectedSignature.equals(parts[2])) {
      throw new IllegalArgumentException("Invalid token signature");
    }

    String payloadJson = new String(Base64Url.decode(parts[1]), StandardCharsets.UTF_8);
    Map<String, String> payload = JsonMapParser.parseObject(payloadJson);
    long expiresAt = Long.parseLong(payload.getOrDefault("exp", "0"));
    if (Instant.now().getEpochSecond() >= expiresAt) {
      throw new IllegalArgumentException("Token expired");
    }

    if (!config.jwtIssuer().equals(payload.get("iss"))) {
      throw new IllegalArgumentException("Invalid token issuer");
    }

    if (!config.jwtAudience().equals(payload.get("aud"))) {
      throw new IllegalArgumentException("Invalid token audience");
    }

    return new AuthContext(
        payload.get("sub"),
        payload.get("name"),
        Role.valueOf(payload.get("role"))
    );
  }
}
