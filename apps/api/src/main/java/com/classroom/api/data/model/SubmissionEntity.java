package com.classroom.api.data.model;

import java.time.Instant;
import java.util.UUID;

public record SubmissionEntity(
    UUID id,
    UUID classroomId,
    UUID questionId,
    UUID userId,
    int attemptNumber,
    String submittedAnswer,
    String submissionStatus,
    Boolean isCorrect,
    Integer awardedScore,
    String reviewStatus,
    Instant submittedAt,
    Instant reviewedAt,
    Integer groupNumber,
    boolean enteredByTeacher,
    boolean groupChallenged,
    boolean buzzWinner
) {
}
