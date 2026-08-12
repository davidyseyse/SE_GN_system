package com.classroom.api.data.model;

import java.time.Instant;
import java.util.UUID;

public record ClassroomMemberEntity(
    UUID id,
    UUID classroomId,
    UUID userId,
    String role,
    String presenceStatus,
    Instant joinedAt,
    Instant lastSeenAt,
    int successfulBuzzCount
) {
}
