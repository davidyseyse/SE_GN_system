package com.classroom.api.auth;

import com.classroom.api.contract.Role;

public record AuthRequest(
    String userId,
    String displayName,
    Role role
) {
}
