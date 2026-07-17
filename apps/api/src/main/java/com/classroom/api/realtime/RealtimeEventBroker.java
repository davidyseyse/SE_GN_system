package com.classroom.api.realtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

public final class RealtimeEventBroker {
  private final Map<String, List<Map<String, Object>>> eventsByClassroom = new ConcurrentHashMap<>();
  private final Map<String, RealtimeSession> sessions = new ConcurrentHashMap<>();
  private final List<RealtimeEventListener> listeners = new CopyOnWriteArrayList<>();

  public RealtimeSession openSession(String classroomCode, String userId, String role) {
    RealtimeSession session = new RealtimeSession(UUID.randomUUID().toString(), classroomCode, userId, role, Instant.now(), Instant.now());
    sessions.put(session.sessionId(), session);
    publish(classroomCode, "SESSION_RESYNCED", Map.of(
        "sessionId", session.sessionId(),
        "userId", userId,
        "role", role
    ));
    return session;
  }

  public RealtimeSession touchSession(String sessionId) {
    RealtimeSession session = sessions.get(sessionId);
    if (session == null) {
      throw new IllegalArgumentException("Realtime session not found");
    }
    RealtimeSession updated = new RealtimeSession(
        session.sessionId(),
        session.classroomCode(),
        session.userId(),
        session.role(),
        session.connectedAt(),
        Instant.now()
    );
    sessions.put(sessionId, updated);
    return updated;
  }

  public void publish(String classroomCode, String eventType, Map<String, Object> payload) {
    List<Map<String, Object>> stream = eventsByClassroom.computeIfAbsent(classroomCode, key -> new CopyOnWriteArrayList<>());
    Map<String, Object> event = new LinkedHashMap<>();
    event.put("type", eventType);
    event.put("classroomCode", classroomCode);
    event.put("occurredAt", Instant.now().toString());
    event.put("payload", payload);
    stream.add(event);
    for (RealtimeEventListener listener : listeners) {
      listener.onEvent(classroomCode, event);
    }
  }

  public List<Map<String, Object>> readEvents(String classroomCode, int sinceIndex) {
    List<Map<String, Object>> stream = eventsByClassroom.getOrDefault(classroomCode, List.of());
    if (sinceIndex < 0 || sinceIndex >= stream.size()) {
      return new ArrayList<>(stream);
    }
    return new ArrayList<>(stream.subList(sinceIndex, stream.size()));
  }

  public void addListener(RealtimeEventListener listener) {
    listeners.add(listener);
  }
}
