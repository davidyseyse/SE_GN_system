package com.classroom.api.data.model;

import java.time.Instant;
import java.util.UUID;

public record QuestionEntity(
    UUID id,
    UUID classroomId,
    int ordinal,
    String type,
    String state,
    String prompt,
    String answerKey,
    String publicationSettings,
    int score,
    Integer answerTimeLimitSeconds,
    UUID createdBy,
    Instant createdAt,
    Instant updatedAt,
    Instant archivedAt
) {
}
