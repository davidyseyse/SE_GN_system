package com.classroom.api.realtime;

import com.classroom.api.auth.AuthContext;
import com.classroom.api.auth.AuthorizationGuard;
import com.classroom.api.contract.Role;
import com.classroom.api.http.HttpHandlerFunction;
import com.classroom.api.http.HttpResponses;
import com.classroom.api.util.JsonMapParser;
import java.util.Map;

public final class RealtimeSessionController {
  private final AuthorizationGuard authorizationGuard;
  private final RealtimeEventBroker eventBroker;

  public RealtimeSessionController(AuthorizationGuard authorizationGuard, RealtimeEventBroker eventBroker) {
    this.authorizationGuard = authorizationGuard;
    this.eventBroker = eventBroker;
  }

  public HttpHandlerFunction openSessionHandler() {
    return exchange -> {
      AuthContext authContext = authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT, Role.STUDENT);
      Map<String, String> request = JsonMapParser.parseObject(exchange.readRequestBody());
      String classroomCode = request.getOrDefault("classroomCode", "").trim();
      if (classroomCode.isEmpty()) {
        throw new IllegalArgumentException("classroomCode is required");
      }

      RealtimeSession session = eventBroker.openSession(classroomCode, authContext.subject(), authContext.role().name());
      HttpResponses.json(exchange, 200, Map.of(
          "sessionId", session.sessionId(),
          "classroomCode", session.classroomCode(),
          "connectedAt", session.connectedAt().toString(),
          "lastSeenAt", session.lastSeenAt().toString()
      ));
    };
  }

  public HttpHandlerFunction resyncHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT, Role.STUDENT);
      String sessionId = exchange.queryParam("sessionId");
      String classroomCode = exchange.queryParam("classroomCode");
      if (sessionId == null || classroomCode == null) {
        throw new IllegalArgumentException("sessionId and classroomCode are required");
      }

      RealtimeSession session = eventBroker.touchSession(sessionId);
      HttpResponses.json(exchange, 200, Map.of(
          "sessionId", session.sessionId(),
          "classroomCode", session.classroomCode(),
          "events", eventBroker.readEvents(classroomCode, 0)
      ));
    };
  }

  public HttpHandlerFunction eventsHandler() {
    return exchange -> {
      authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT, Role.STUDENT);
      String classroomCode = exchange.queryParam("classroomCode");
      if (classroomCode == null || classroomCode.isBlank()) {
        throw new IllegalArgumentException("classroomCode query parameter is required");
      }

      int since = 0;
      String sinceValue = exchange.queryParam("since");
      if (sinceValue != null && !sinceValue.isBlank()) {
        since = Integer.parseInt(sinceValue);
      }

      HttpResponses.json(exchange, 200, Map.of(
          "classroomCode", classroomCode,
          "events", eventBroker.readEvents(classroomCode, since)
      ));
    };
  }
}
