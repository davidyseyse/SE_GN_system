package com.classroom.api.realtime;

import com.classroom.api.config.ServerConfig;
import com.classroom.api.http.HttpHandlerFunction;
import com.classroom.api.http.HttpResponses;
import java.time.Instant;
import java.util.Map;

public final class RealtimeController {
  private final ServerConfig config;
  private final RealtimeEventBroker eventBroker;

  public RealtimeController(ServerConfig config, RealtimeEventBroker eventBroker) {
    this.config = config;
    this.eventBroker = eventBroker;
  }

  public HttpHandlerFunction configHandler() {
    return exchange -> HttpResponses.json(exchange, 200, Map.of(
        "transport", "websocket",
        "url", config.endpoints().websocketUrl(),
        "path", config.endpoints().websocketPath(),
        "topicPrefix", config.endpoints().websocketTopicPrefix(),
        "auth", Map.of(
            "type", "bearer",
            "audience", config.jwtAudience(),
            "issuer", config.jwtIssuer()
        ),
        "fallback", Map.of(
            "sessionPath", "/api/realtime/session",
            "eventsPath", "/api/realtime/events",
            "resyncPath", "/api/realtime/resync"
        ),
        "bufferedEventCount", eventBroker.readEvents("system", 0).size(),
        "serverTime", Instant.now().toString()
    ));
  }
}
