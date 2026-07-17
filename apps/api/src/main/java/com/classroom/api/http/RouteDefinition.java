package com.classroom.api.http;

public record RouteDefinition(
    String method,
    String path,
    HttpHandlerFunction handler
) {
}
