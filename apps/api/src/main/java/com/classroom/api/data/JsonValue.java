package com.classroom.api.data;

public final class JsonValue {
  private JsonValue() {
  }

  public static String normalize(String raw) {
    if (raw == null || raw.isBlank()) {
      return "null";
    }

    String trimmed = raw.trim();
    if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.startsWith("\"")) {
      return trimmed;
    }

    if ("true".equalsIgnoreCase(trimmed) || "false".equalsIgnoreCase(trimmed) || "null".equalsIgnoreCase(trimmed)) {
      return trimmed.toLowerCase();
    }

    if (trimmed.matches("-?\\d+(\\.\\d+)?")) {
      return trimmed;
    }

    return "\"" + escape(trimmed) + "\"";
  }

  private static String escape(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"");
  }
}
