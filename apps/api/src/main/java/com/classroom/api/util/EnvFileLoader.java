package com.classroom.api.util;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class EnvFileLoader {
  private EnvFileLoader() {
  }

  public static Map<String, String> load() {
    Map<String, String> values = new LinkedHashMap<>();
    Path cursor = Path.of("").toAbsolutePath();

    while (cursor != null) {
      Path envFile = cursor.resolve(".env");
      if (Files.exists(envFile)) {
        values.putAll(readEnvFile(envFile));
        return values;
      }
      cursor = cursor.getParent();
    }

    return values;
  }

  private static Map<String, String> readEnvFile(Path envFile) {
    Map<String, String> values = new LinkedHashMap<>();

    try {
      List<String> lines = Files.readAllLines(envFile, StandardCharsets.UTF_8);
      for (String line : lines) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }

        int separatorIndex = trimmed.indexOf('=');
        if (separatorIndex <= 0) {
          continue;
        }

        String key = trimmed.substring(0, separatorIndex).trim();
        String rawValue = trimmed.substring(separatorIndex + 1).trim();
        values.put(key, stripQuotes(rawValue));
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to read " + envFile, exception);
    }

    return values;
  }

  private static String stripQuotes(String value) {
    if (value.length() >= 2) {
      boolean wrappedInDoubleQuotes = value.startsWith("\"") && value.endsWith("\"");
      boolean wrappedInSingleQuotes = value.startsWith("'") && value.endsWith("'");
      if (wrappedInDoubleQuotes || wrappedInSingleQuotes) {
        return value.substring(1, value.length() - 1);
      }
    }

    return value;
  }
}
