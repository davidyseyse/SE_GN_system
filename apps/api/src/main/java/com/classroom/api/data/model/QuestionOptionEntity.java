package com.classroom.api.data.model;

import java.util.UUID;

public record QuestionOptionEntity(
    UUID id,
    UUID questionId,
    int ordinal,
    String label,
    String content,
    boolean isCorrect
) {
}
