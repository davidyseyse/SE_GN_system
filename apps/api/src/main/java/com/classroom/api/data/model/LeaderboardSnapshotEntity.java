package com.classroom.api.data.model;

import java.time.Instant;
import java.util.UUID;

public record LeaderboardSnapshotEntity(
    UUID id,
    UUID classroomId,
    Instant snapshotAt,
    String rankingData,
    String visibility
) {
}
