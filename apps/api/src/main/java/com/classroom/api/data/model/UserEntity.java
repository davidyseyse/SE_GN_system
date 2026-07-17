package com.classroom.api.data.model;

import java.time.Instant;
import java.util.UUID;

public record UserEntity(
    UUID id,
    String externalId,
    String displayName,
    String role,
    Instant createdAt,
    Instant updatedAt
) {
}
