package com.classroom.api.data.model;

import java.time.Instant;
import java.util.UUID;

public record ClassroomEntity(
    UUID id,
    UUID teacherId,
    String classroomCode,
    String name,
    String status,
    String participationSettings,
    String leaderboardVisibility,
    UUID activeQuestionId,
    Instant createdAt,
    Instant updatedAt,
    Instant openedAt,
    Instant closedAt
) {
}
