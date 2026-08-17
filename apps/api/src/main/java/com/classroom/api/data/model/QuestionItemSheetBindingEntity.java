package com.classroom.api.data.model;

import java.util.UUID;

public record QuestionItemSheetBindingEntity(
    UUID itemId,
    UUID classroomId,
    UUID sourceSheetConfigId,
    String sourceWorksheet,
    String sourceCell,
    String sourceMarker,
    UUID answerSheetConfigId,
    String answerWorksheet,
    String targetCell
) {
}

