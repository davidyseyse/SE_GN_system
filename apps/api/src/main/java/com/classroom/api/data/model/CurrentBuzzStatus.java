package com.classroom.api.data.model;

import java.util.UUID;

public record CurrentBuzzStatus(
    UUID questionId,
    int roundNumber,
    String buzzStatus,
    UUID winnerUserId,
    String winnerExternalUserId,
    String winnerDisplayName,
    String submissionStatus,
    String reviewStatus
) {
}
