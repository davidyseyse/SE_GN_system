package com.classroom.api.util;

import java.util.LinkedHashMap;
import java.util.Map;

public final class JsonMapParser {
  private JsonMapParser() {
  }

  public static Map<String, String> parseObject(String json) {
    Map<String, String> values = new LinkedHashMap<>();
    if (json == null) {
      return values;
    }

    String trimmed = json.trim();
    if (trimmed.isEmpty() || "{}".equals(trimmed)) {
      return values;
    }

    if (!trimmed.startsWith("{") || !trimmed.endsWith("}")) {
      throw new IllegalArgumentException("Expected a JSON object");
    }

    String content = trimmed.substring(1, trimmed.length() - 1).trim();
    if (content.isEmpty()) {
      return values;
    }

    int cursor = 0;
    while (cursor < content.length()) {
      cursor = skipWhitespaceAndCommas(content, cursor);
      if (cursor >= content.length()) {
        break;
      }

      if (content.charAt(cursor) != '"') {
        throw new IllegalArgumentException("Expected quoted JSON key");
      }

      int keyEnd = findStringEnd(content, cursor + 1);
      String key = unescape(content.substring(cursor + 1, keyEnd));
      cursor = skipWhitespace(content, keyEnd + 1);
      if (cursor >= content.length() || content.charAt(cursor) != ':') {
        throw new IllegalArgumentException("Expected ':' after JSON key");
      }

      cursor = skipWhitespace(content, cursor + 1);
      ParseResult result = parseValue(content, cursor);
      values.put(key, result.value());
      cursor = result.nextIndex();
    }

    return values;
  }

  private static ParseResult parseValue(String content, int startIndex) {
    if (startIndex >= content.length()) {
      return new ParseResult("", startIndex);
    }

    char marker = content.charAt(startIndex);
    if (marker == '"') {
      int end = findStringEnd(content, startIndex + 1);
      return new ParseResult(unescape(content.substring(startIndex + 1, end)), end + 1);
    }

    int cursor = startIndex;
    int depth = 0;
    while (cursor < content.length()) {
      char current = content.charAt(cursor);
      if (current == '{' || current == '[') {
        depth++;
      } else if (current == '}' || current == ']') {
        if (depth == 0) {
          break;
        }
        depth--;
      } else if (current == ',' && depth == 0) {
        break;
      }
      cursor++;
    }

    return new ParseResult(content.substring(startIndex, cursor).trim(), cursor);
  }

  private static int skipWhitespace(String content, int startIndex) {
    int cursor = startIndex;
    while (cursor < content.length() && Character.isWhitespace(content.charAt(cursor))) {
      cursor++;
    }
    return cursor;
  }

  private static int skipWhitespaceAndCommas(String content, int startIndex) {
    int cursor = startIndex;
    while (cursor < content.length()) {
      char current = content.charAt(cursor);
      if (Character.isWhitespace(current) || current == ',') {
        cursor++;
        continue;
      }
      break;
    }
    return cursor;
  }

  private static int findStringEnd(String content, int startIndex) {
    boolean escaped = false;
    for (int index = startIndex; index < content.length(); index++) {
      char current = content.charAt(index);
      if (escaped) {
        escaped = false;
        continue;
      }
      if (current == '\\') {
        escaped = true;
        continue;
      }
      if (current == '"') {
        return index;
      }
    }
    throw new IllegalArgumentException("Unterminated JSON string");
  }

  private static String unescape(String value) {
    return value
        .replace("\\\"", "\"")
        .replace("\\\\", "\\")
        .replace("\\n", "\n")
        .replace("\\r", "\r")
        .replace("\\t", "\t");
  }

  private record ParseResult(String value, int nextIndex) {
  }
}
