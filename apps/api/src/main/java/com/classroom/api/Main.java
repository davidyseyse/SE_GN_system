package com.classroom.api;

import com.classroom.api.config.ServerConfig;
import com.classroom.api.http.ApiServer;
import java.io.IOException;

public final class Main {
  private Main() {
  }

  public static void main(String[] args) throws IOException {
    ServerConfig config = ServerConfig.load();
    ApiServer server = new ApiServer(config);
    Runtime.getRuntime().addShutdownHook(new Thread(server::close, "api-shutdown"));
    server.start();
    System.out.println("Java API listening on " + config.host() + ":" + config.port());
    System.out.println("WebSocket listening on " + config.host() + ":" + config.websocketPort() + " " + config.endpoints().websocketPath());
  }
}
