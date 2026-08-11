package com.classroom.api.sheets;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SheetAnswerFormatter {
  private static final Pattern NUMBERED_GROUP = Pattern.compile("^第\\s*(\\d+)\\s*組$");

  private SheetAnswerFormatter() {
  }

  public static String format(String answer, String displayName) {
    return format(answer, extractGroupNumber(displayName));
  }

  public static String format(String answer, Integer groupNumber) {
    String safeAnswer = answer == null ? "" : answer;
    return groupNumber == null || groupNumber <= 0 ? safeAnswer : safeAnswer + "_G" + groupNumber;
  }

  public static Integer extractGroupNumber(String displayName) {
    String group = extractGroup(displayName);
    try {
      int value = Integer.parseInt(group);
      return value > 0 ? value : null;
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  static String extractGroup(String displayName) {
    if (displayName == null || displayName.isBlank()) {
      return "";
    }
    String trimmed = displayName.trim();
    int separator = trimmed.lastIndexOf('/');
    if (separator >= 0 && separator + 1 < trimmed.length()) {
      return normalizeGroup(trimmed.substring(separator + 1).trim());
    }
    return normalizeGroup(trimmed);
  }

  private static String normalizeGroup(String value) {
    Matcher matcher = NUMBERED_GROUP.matcher(value);
    return matcher.matches() ? matcher.group(1) : value;
  }
}
