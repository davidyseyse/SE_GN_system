package com.classroom.api.http.json;

import java.util.Iterator;
import java.util.Map;

public final class JsonWriter {
  public JsonWriter() {
  }

  public static String toJson(Object value) {
    if (value == null) {
      return "null";
    }

    if (value instanceof String stringValue) {
      return "\"" + escape(stringValue) + "\"";
    }

    if (value instanceof Number || value instanceof Boolean) {
      return value.toString();
    }

    if (value instanceof Map<?, ?> mapValue) {
      StringBuilder builder = new StringBuilder();
      builder.append("{");
      Iterator<? extends Map.Entry<?, ?>> iterator = mapValue.entrySet().iterator();

      while (iterator.hasNext()) {
        Map.Entry<?, ?> entry = iterator.next();
        builder.append(toJson(String.valueOf(entry.getKey())));
        builder.append(":");
        builder.append(toJson(entry.getValue()));
        if (iterator.hasNext()) {
          builder.append(",");
        }
      }

      builder.append("}");
      return builder.toString();
    }

    if (value instanceof Iterable<?> iterableValue) {
      StringBuilder builder = new StringBuilder();
      builder.append("[");
      Iterator<?> iterator = iterableValue.iterator();

      while (iterator.hasNext()) {
        builder.append(toJson(iterator.next()));
        if (iterator.hasNext()) {
          builder.append(",");
        }
      }

      builder.append("]");
      return builder.toString();
    }

    return "\"" + escape(value.toString()) + "\"";
  }

  private static String escape(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\t", "\\t");
  }
}
