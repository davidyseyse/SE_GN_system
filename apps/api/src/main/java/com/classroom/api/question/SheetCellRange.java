package com.classroom.api.question;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SheetCellRange {
  private static final Pattern SINGLE_CELL = Pattern.compile(
      "^(?:'((?:[^']|'')+)'|([^'!]+))?!?([A-Za-z]{1,3})([1-9][0-9]{0,6})$");

  private SheetCellRange() {
  }

  public static String normalize(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("targetCell is required");
    }
    String trimmed = value.trim();
    if (trimmed.contains(":")) {
      throw new IllegalArgumentException("targetCell must identify one cell");
    }
    Matcher matcher = SINGLE_CELL.matcher(trimmed);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("targetCell must be a valid single-cell A1 range");
    }
    String sheet = matcher.group(1) != null
        ? "'" + matcher.group(1) + "'!"
        : matcher.group(2) != null ? matcher.group(2).trim() + "!" : "";
    return sheet + matcher.group(3).toUpperCase(Locale.ROOT) + matcher.group(4);
  }
}
