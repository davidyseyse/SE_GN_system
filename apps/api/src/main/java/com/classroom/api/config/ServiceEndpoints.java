package com.classroom.api.config;

public record ServiceEndpoints(
    String apiBaseUrl,
    String websocketUrl,
    String websocketPath,
    String websocketTopicPrefix
) {
}
