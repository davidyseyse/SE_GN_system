package com.classroom.api.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class JsonParser {
  private JsonParser() {
  }

  public static Object parse(String json) {
    Cursor cursor = new Cursor(json == null ? "" : json);
    Object value = cursor.readValue();
    cursor.skipWhitespace();
    if (!cursor.atEnd()) {
      throw new IllegalArgumentException("Unexpected content after JSON value");
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> parseObject(String json) {
    Object value = parse(json);
    if (!(value instanceof Map<?, ?>)) {
      throw new IllegalArgumentException("Expected a JSON object");
    }
    return (Map<String, Object>) value;
  }

  private static final class Cursor {
    private final String input;
    private int index;

    private Cursor(String input) {
      this.input = input;
    }

    private Object readValue() {
      skipWhitespace();
      if (atEnd()) throw new IllegalArgumentException("Expected JSON value");
      return switch (input.charAt(index)) {
        case '{' -> readObject();
        case '[' -> readArray();
        case '"' -> readString();
        case 't' -> readLiteral("true", Boolean.TRUE);
        case 'f' -> readLiteral("false", Boolean.FALSE);
        case 'n' -> readLiteral("null", null);
        default -> readNumber();
      };
    }

    private Map<String, Object> readObject() {
      LinkedHashMap<String, Object> result = new LinkedHashMap<>();
      expect('{');
      skipWhitespace();
      if (consume('}')) return result;
      while (true) {
        skipWhitespace();
        if (atEnd() || input.charAt(index) != '"') {
          throw new IllegalArgumentException("Expected quoted JSON key");
        }
        String key = readString();
        skipWhitespace();
        expect(':');
        result.put(key, readValue());
        skipWhitespace();
        if (consume('}')) return result;
        expect(',');
      }
    }

    private List<Object> readArray() {
      ArrayList<Object> result = new ArrayList<>();
      expect('[');
      skipWhitespace();
      if (consume(']')) return result;
      while (true) {
        result.add(readValue());
        skipWhitespace();
        if (consume(']')) return result;
        expect(',');
      }
    }

    private String readString() {
      expect('"');
      StringBuilder result = new StringBuilder();
      while (!atEnd()) {
        char current = input.charAt(index++);
        if (current == '"') return result.toString();
        if (current != '\\') {
          result.append(current);
          continue;
        }
        if (atEnd()) throw new IllegalArgumentException("Unterminated JSON escape");
        char escaped = input.charAt(index++);
        switch (escaped) {
          case '"', '\\', '/' -> result.append(escaped);
          case 'b' -> result.append('\b');
          case 'f' -> result.append('\f');
          case 'n' -> result.append('\n');
          case 'r' -> result.append('\r');
          case 't' -> result.append('\t');
          case 'u' -> {
            if (index + 4 > input.length()) throw new IllegalArgumentException("Invalid unicode escape");
            result.append((char) Integer.parseInt(input.substring(index, index + 4), 16));
            index += 4;
          }
          default -> throw new IllegalArgumentException("Invalid JSON escape");
        }
      }
      throw new IllegalArgumentException("Unterminated JSON string");
    }

    private Object readNumber() {
      int start = index;
      if (consume('-')) { /* sign */ }
      while (!atEnd() && Character.isDigit(input.charAt(index))) index++;
      boolean decimal = false;
      if (consume('.')) {
        decimal = true;
        while (!atEnd() && Character.isDigit(input.charAt(index))) index++;
      }
      if (!atEnd() && (input.charAt(index) == 'e' || input.charAt(index) == 'E')) {
        decimal = true;
        index++;
        if (!atEnd() && (input.charAt(index) == '+' || input.charAt(index) == '-')) index++;
        while (!atEnd() && Character.isDigit(input.charAt(index))) index++;
      }
      if (start == index) throw new IllegalArgumentException("Invalid JSON value");
      String value = input.substring(start, index);
      try {
        return decimal ? Double.parseDouble(value) : Long.parseLong(value);
      } catch (NumberFormatException exception) {
        throw new IllegalArgumentException("Invalid JSON number", exception);
      }
    }

    private Object readLiteral(String literal, Object value) {
      if (!input.startsWith(literal, index)) throw new IllegalArgumentException("Invalid JSON literal");
      index += literal.length();
      return value;
    }

    private void skipWhitespace() {
      while (!atEnd() && Character.isWhitespace(input.charAt(index))) index++;
    }

    private boolean consume(char expected) {
      if (!atEnd() && input.charAt(index) == expected) {
        index++;
        return true;
      }
      return false;
    }

    private void expect(char expected) {
      if (!consume(expected)) throw new IllegalArgumentException("Expected '" + expected + "'");
    }

    private boolean atEnd() {
      return index >= input.length();
    }
  }
}
