package com.classroom.api.score;

import com.classroom.api.util.JsonMapParser;
import java.util.Map;

public record ClassroomScoringPolicy(
    int buzzBonus,
    int wrongAnswerPenalty
) {
  public static ClassroomScoringPolicy fromSettings(String participationSettings) {
    Map<String, String> values = JsonMapParser.parseObject(participationSettings == null ? "{}" : participationSettings);
    int buzzBonus = parseInteger(values.get("buzzBonus"), 0);
    int wrongAnswerPenalty = parseInteger(values.get("wrongAnswerPenalty"), 0);
    return new ClassroomScoringPolicy(buzzBonus, wrongAnswerPenalty);
  }

  private static int parseInteger(String value, int fallback) {
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      return fallback;
    }
  }
}
