package com.classroom.api.data.model;

import java.time.Instant;
import java.util.UUID;

public record BuzzRecordEntity(
    UUID id,
    UUID classroomId,
    UUID questionId,
    UUID userId,
    String requestId,
    long receiptOrder,
    Instant receivedAt,
    String outcomeStatus,
    boolean successful,
    Instant answerDeadlineAt
) {
}
