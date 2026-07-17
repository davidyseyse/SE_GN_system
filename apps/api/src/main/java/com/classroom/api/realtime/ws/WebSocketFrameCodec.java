package com.classroom.api.realtime.ws;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

public final class WebSocketFrameCodec {
  private static final String WEBSOCKET_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

  private WebSocketFrameCodec() {
  }

  public static String acceptKey(String key) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-1");
      byte[] hash = digest.digest((key + WEBSOCKET_GUID).getBytes(StandardCharsets.UTF_8));
      return Base64.getEncoder().encodeToString(hash);
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to compute WebSocket accept key", exception);
    }
  }

  public static void writeText(BufferedOutputStream output, String message) throws IOException {
    byte[] payload = message.getBytes(StandardCharsets.UTF_8);
    output.write(0x81);
    if (payload.length <= 125) {
      output.write(payload.length);
    } else if (payload.length <= 65535) {
      output.write(126);
      output.write((payload.length >>> 8) & 0xFF);
      output.write(payload.length & 0xFF);
    } else {
      output.write(127);
      output.write(ByteBuffer.allocate(8).putLong(payload.length).array());
    }
    output.write(payload);
    output.flush();
  }

  public static int readOpcode(BufferedInputStream input) throws IOException {
    int firstByte = input.read();
    if (firstByte == -1) {
      return -1;
    }
    int secondByte = input.read();
    if (secondByte == -1) {
      return -1;
    }
    long payloadLength = secondByte & 0x7F;
    if (payloadLength == 126) {
      payloadLength = ((input.read() & 0xFF) << 8) | (input.read() & 0xFF);
    } else if (payloadLength == 127) {
      byte[] lengthBytes = input.readNBytes(8);
      payloadLength = ByteBuffer.wrap(lengthBytes).getLong();
    }
    boolean masked = (secondByte & 0x80) != 0;
    byte[] mask = masked ? input.readNBytes(4) : new byte[0];
    byte[] payload = input.readNBytes((int) payloadLength);
    if (masked) {
      for (int index = 0; index < payload.length; index++) {
        payload[index] = (byte) (payload[index] ^ mask[index % 4]);
      }
    }
    return firstByte & 0x0F;
  }
}
