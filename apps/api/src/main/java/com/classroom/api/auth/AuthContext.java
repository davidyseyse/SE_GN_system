package com.classroom.api.auth;

import com.classroom.api.contract.Role;

public record AuthContext(
    String subject,
    String displayName,
    Role role
) {
}
