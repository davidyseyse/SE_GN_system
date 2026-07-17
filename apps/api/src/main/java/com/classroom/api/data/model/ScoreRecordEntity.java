package com.classroom.api.data.model;

import java.time.Instant;
import java.util.UUID;

public record ScoreRecordEntity(
    UUID id,
    UUID classroomId,
    UUID userId,
    UUID questionId,
    String sourceType,
    int deltaScore,
    int runningTotal,
    Instant createdAt
) {
}
