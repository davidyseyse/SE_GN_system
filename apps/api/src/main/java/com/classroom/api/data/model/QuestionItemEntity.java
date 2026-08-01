package com.classroom.api.data.model;

import java.util.UUID;

public record QuestionItemEntity(
    UUID id,
    UUID questionId,
    int ordinal,
    String prompt,
    String targetCell
) {
}
