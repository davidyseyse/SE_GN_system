package com.classroom.api.data.model;

import java.time.Instant;
import java.util.UUID;

public record AuditLogEntity(
    UUID id,
    UUID classroomId,
    UUID actorUserId,
    String actionType,
    String targetType,
    UUID targetId,
    String metadata,
    Instant createdAt
) {
}
