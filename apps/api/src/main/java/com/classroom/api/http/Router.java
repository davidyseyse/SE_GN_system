package com.classroom.api.http;

import java.util.ArrayList;
import java.util.List;

public final class Router {
  private final List<RouteDefinition> routes = new ArrayList<>();

  public Router add(String method, String path, HttpHandlerFunction handler) {
    routes.add(new RouteDefinition(method.toUpperCase(), path, handler));
    return this;
  }

  public RouteDefinition match(String method, String path) {
    for (RouteDefinition route : routes) {
      if (route.method().equalsIgnoreCase(method) && route.path().equals(path)) {
        return route;
      }
    }
    return null;
  }
}
