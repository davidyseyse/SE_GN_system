package com.classroom.api.data.model;

import java.time.Instant;
import java.util.UUID;

public record SheetConfigEntity(
    UUID id,
    String name,
    String sheetId,
    Instant createdAt,
    Instant updatedAt
) {
}
