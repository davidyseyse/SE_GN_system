package com.classroom.api.http;

import java.io.IOException;

@FunctionalInterface
public interface HttpHandlerFunction {
  void handle(HttpExchangeAdapter exchange) throws IOException;
}
