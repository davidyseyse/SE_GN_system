package com.classroom.api.realtime;

import java.util.Map;

@FunctionalInterface
public interface RealtimeEventListener {
  void onEvent(String classroomCode, Map<String, Object> event);
}
