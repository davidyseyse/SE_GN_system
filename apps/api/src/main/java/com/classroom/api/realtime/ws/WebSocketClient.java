package com.classroom.api.realtime.ws;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.Socket;

public record WebSocketClient(
    String sessionId,
    String classroomCode,
    String userId,
    String role,
    Socket socket,
    BufferedInputStream input,
    BufferedOutputStream output
) {
}
