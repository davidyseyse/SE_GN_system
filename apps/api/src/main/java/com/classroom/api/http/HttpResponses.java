package com.classroom.api.http;

import com.classroom.api.http.json.JsonWriter;
import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public final class HttpResponses {
  private static final JsonWriter JSON_WRITER = new JsonWriter();

  private HttpResponses() {
  }

  public static void json(HttpExchangeAdapter exchange, int statusCode, Map<String, ?> payload) throws IOException {
    json(exchange.raw(), statusCode, payload);
  }

  public static void json(HttpExchange exchange, int statusCode, Map<String, ?> payload) throws IOException {
    byte[] body = JSON_WRITER.toJson(payload).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
    exchange.sendResponseHeaders(statusCode, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }
}
