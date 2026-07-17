package com.classroom.api.realtime;

import java.time.Instant;

public record RealtimeSession(
    String sessionId,
    String classroomCode,
    String userId,
    String role,
    Instant connectedAt,
    Instant lastSeenAt
) {
}
