package com.classroom.api.http;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class HttpExchangeAdapter {
  private final HttpExchange exchange;

  public HttpExchangeAdapter(HttpExchange exchange) {
    this.exchange = exchange;
  }

  public HttpExchange raw() {
    return exchange;
  }

  public String method() {
    return exchange.getRequestMethod();
  }

  public String path() {
    return exchange.getRequestURI().getPath();
  }

  public String queryParam(String key) {
    String rawQuery = exchange.getRequestURI().getQuery();
    if (rawQuery == null || rawQuery.isBlank()) {
      return null;
    }

    Map<String, String> query = new LinkedHashMap<>();
    for (String pair : rawQuery.split("&")) {
      String[] parts = pair.split("=", 2);
      if (parts.length == 2) {
        query.put(parts[0], parts[1]);
      }
    }
    return query.get(key);
  }

  public String header(String name) {
    return exchange.getRequestHeaders().getFirst(name);
  }

  public String readRequestBody() throws IOException {
    InputStream bodyStream = exchange.getRequestBody();
    return new String(bodyStream.readAllBytes(), StandardCharsets.UTF_8);
  }
}
