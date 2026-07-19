package com.classroom.api.redis;

import com.classroom.api.config.ServerConfig;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;

public final class RedisBuzzLockService {
  private final String host;
  private final int port;

  public RedisBuzzLockService(ServerConfig config) {
    URI uri = URI.create(config.redisUrl());
    this.host = uri.getHost();
    this.port = uri.getPort() == -1 ? 6379 : uri.getPort();
  }

  public boolean tryLock(String key, String value, int ttlSeconds) {
    String response = send("SET", key, value, "NX", "EX", String.valueOf(ttlSeconds));
    return "OK".equals(response);
  }

  public long nextSequence(String key) {
    String response = send("INCR", key);
    return Long.parseLong(response);
  }

  public void delete(String key) {
    send("DEL", key);
  }

  private String send(String... args) {
    try (Socket socket = new Socket(host, port);
         BufferedOutputStream output = new BufferedOutputStream(socket.getOutputStream());
         BufferedInputStream input = new BufferedInputStream(socket.getInputStream())) {
      output.write(encode(args));
      output.flush();
      return readResponse(input);
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to communicate with Redis", exception);
    }
  }

  private static byte[] encode(String... args) {
    StringBuilder command = new StringBuilder();
    command.append("*").append(args.length).append("\r\n");
    for (String arg : args) {
      byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
      command.append("$").append(bytes.length).append("\r\n");
      command.append(arg).append("\r\n");
    }
    return command.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static String readResponse(BufferedInputStream input) throws IOException {
    int prefix = input.read();
    if (prefix == -1) {
      throw new IOException("Redis connection closed");
    }

    return switch ((char) prefix) {
      case '+', ':', '-' -> readLine(input);
      case '$' -> {
        int length = Integer.parseInt(readLine(input));
        if (length < 0) {
          yield "";
        }
        byte[] data = input.readNBytes(length);
        input.read();
        input.read();
        yield new String(data, StandardCharsets.UTF_8);
      }
      default -> throw new IOException("Unsupported Redis response type: " + (char) prefix);
    };
  }

  private static String readLine(BufferedInputStream input) throws IOException {
    StringBuilder builder = new StringBuilder();
    while (true) {
      int value = input.read();
      if (value == '\r') {
        input.read();
        return builder.toString();
      }
      builder.append((char) value);
    }
  }
}
