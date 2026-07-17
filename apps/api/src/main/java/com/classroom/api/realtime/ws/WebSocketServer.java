package com.classroom.api.realtime.ws;

import com.classroom.api.auth.AuthContext;
import com.classroom.api.auth.AuthTokenService;
import com.classroom.api.config.ServerConfig;
import com.classroom.api.http.json.JsonWriter;
import com.classroom.api.realtime.RealtimeEventBroker;
import com.classroom.api.realtime.RealtimeSession;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class WebSocketServer {
  private final ServerConfig config;
  private final AuthTokenService authTokenService;
  private final RealtimeEventBroker eventBroker;
  private final Map<String, WebSocketClient> clients = new ConcurrentHashMap<>();
  private final JsonWriter jsonWriter = new JsonWriter();
  private ServerSocket serverSocket;

  public WebSocketServer(ServerConfig config, AuthTokenService authTokenService, RealtimeEventBroker eventBroker) {
    this.config = config;
    this.authTokenService = authTokenService;
    this.eventBroker = eventBroker;
    this.eventBroker.addListener(this::broadcast);
  }

  public void start() throws IOException {
    serverSocket = new ServerSocket(config.websocketPort());
    Thread acceptThread = new Thread(this::acceptLoop, "ws-accept");
    acceptThread.setDaemon(true);
    acceptThread.start();
  }

  private void acceptLoop() {
    while (!serverSocket.isClosed()) {
      try {
        Socket socket = serverSocket.accept();
        Thread clientThread = new Thread(() -> handleClient(socket), "ws-client-" + socket.getPort());
        clientThread.setDaemon(true);
        clientThread.start();
      } catch (IOException exception) {
        return;
      }
    }
  }

  private void handleClient(Socket socket) {
    try (socket;
         BufferedInputStream input = new BufferedInputStream(socket.getInputStream());
         BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream())) {
      HandshakeRequest handshake = readHandshake(input);
      if (!config.endpoints().websocketPath().equals(handshake.path())) {
        writeBadRequest(output, "Unsupported websocket path");
        return;
      }

      AuthContext authContext = authTokenService.verify(requiredQuery(handshake, "token"));
      String classroomCode = requiredQuery(handshake, "classroomCode");
      String sessionId = handshake.queryParams().get("sessionId");
      RealtimeSession session = sessionId == null || sessionId.isBlank()
          ? eventBroker.openSession(classroomCode, authContext.subject(), authContext.role().name())
          : eventBroker.touchSession(sessionId);

      writeHandshakeResponse(output, handshake.headers().get("sec-websocket-key"));
      WebSocketClient client = new WebSocketClient(session.sessionId(), classroomCode, authContext.subject(), authContext.role().name(), socket, input, output);
      clients.put(client.sessionId(), client);

      Map<String, Object> syncEvent = new LinkedHashMap<>();
      syncEvent.put("type", "session.resynced");
      syncEvent.put("classroomCode", classroomCode);
      syncEvent.put("occurredAt", Instant.now().toString());
      syncEvent.put("payload", Map.of(
          "sessionId", session.sessionId(),
          "events", eventBroker.readEvents(classroomCode, 0)
      ));
      WebSocketFrameCodec.writeText(output, jsonWriter.toJson(syncEvent));

      readLoop(client);
    } catch (Exception exception) {
      // connection closed or invalid handshake
    }
  }

  private void readLoop(WebSocketClient client) throws IOException {
    while (true) {
      int opcode = WebSocketFrameCodec.readOpcode(client.input());
      if (opcode == -1 || opcode == 0x8) {
        clients.remove(client.sessionId());
        return;
      }
    }
  }

  private void broadcast(String classroomCode, Map<String, Object> event) {
    for (WebSocketClient client : List.copyOf(clients.values())) {
      if (!client.classroomCode().equals(classroomCode)) {
        continue;
      }
      try {
        WebSocketFrameCodec.writeText(client.output(), jsonWriter.toJson(event));
      } catch (IOException exception) {
        clients.remove(client.sessionId());
      }
    }
  }

  private static HandshakeRequest readHandshake(BufferedInputStream input) throws IOException {
    String requestLine = readHttpLine(input);
    String[] requestParts = requestLine.split(" ");
    Map<String, String> headers = new LinkedHashMap<>();
    String line;
    while (!(line = readHttpLine(input)).isEmpty()) {
      int separatorIndex = line.indexOf(':');
      if (separatorIndex <= 0) {
        continue;
      }
      headers.put(line.substring(0, separatorIndex).trim().toLowerCase(), line.substring(separatorIndex + 1).trim());
    }

    String target = requestParts[1];
    String path = target.contains("?") ? target.substring(0, target.indexOf('?')) : target;
    Map<String, String> query = new LinkedHashMap<>();
    if (target.contains("?")) {
      String rawQuery = target.substring(target.indexOf('?') + 1);
      for (String pair : rawQuery.split("&")) {
        String[] parts = pair.split("=", 2);
        if (parts.length == 2) {
          query.put(parts[0], java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
      }
    }
    return new HandshakeRequest(path, query, headers);
  }

  private static String readHttpLine(BufferedInputStream input) throws IOException {
    StringBuilder builder = new StringBuilder();
    while (true) {
      int value = input.read();
      if (value == -1) {
        return builder.toString();
      }
      if (value == '\r') {
        input.read();
        return builder.toString();
      }
      builder.append((char) value);
    }
  }

  private static void writeHandshakeResponse(BufferedOutputStream output, String websocketKey) throws IOException {
    String response = "HTTP/1.1 101 Switching Protocols\r\n"
        + "Upgrade: websocket\r\n"
        + "Connection: Upgrade\r\n"
        + "Sec-WebSocket-Accept: " + WebSocketFrameCodec.acceptKey(websocketKey) + "\r\n\r\n";
    output.write(response.getBytes(StandardCharsets.UTF_8));
    output.flush();
  }

  private static void writeBadRequest(BufferedOutputStream output, String message) throws IOException {
    String response = "HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: "
        + message.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + message;
    output.write(response.getBytes(StandardCharsets.UTF_8));
    output.flush();
  }

  private static String requiredQuery(HandshakeRequest request, String key) {
    String value = request.queryParams().get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(key + " is required");
    }
    return value;
  }

  private record HandshakeRequest(String path, Map<String, String> queryParams, Map<String, String> headers) {
  }
}
