package com.classroom.api.data.model;

import java.time.Instant;
import java.util.UUID;

public record CurrentBuzzStatus(
    UUID questionId,
    int roundNumber,
    String buzzStatus,
    UUID winnerUserId,
    String winnerExternalUserId,
    String winnerDisplayName,
    Instant answerDeadlineAt,
    String submissionStatus,
    String reviewStatus
) {
}
