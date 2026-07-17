package com.classroom.api.auth;

import com.classroom.api.contract.Role;

public record AuthResponse(
    String accessToken,
    String tokenType,
    long expiresInSeconds,
    String userId,
    String displayName,
    Role role
) {
}
