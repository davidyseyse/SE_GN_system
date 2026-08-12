package com.classroom.api.score;

import com.classroom.api.util.JsonMapParser;
import java.util.Map;

public record ClassroomScoringPolicy(
    int buzzBonus,
    int wrongAnswerPenalty,
    int successfulBuzzLimit
) {
  public static final int GLOBAL_SUCCESSFUL_BUZZ_LIMIT = 1;

  public static ClassroomScoringPolicy fromSettings(String participationSettings) {
    Map<String, String> values = JsonMapParser.parseObject(participationSettings == null ? "{}" : participationSettings);
    int buzzBonus = parseInteger(values.get("buzzBonus"), 0);
    int wrongAnswerPenalty = parseInteger(values.get("wrongAnswerPenalty"), 0);
    return new ClassroomScoringPolicy(buzzBonus, wrongAnswerPenalty, GLOBAL_SUCCESSFUL_BUZZ_LIMIT);
  }

  public static void validateSettings(String participationSettings) {
    Map<String, String> values = JsonMapParser.parseObject(
        participationSettings == null ? "{}" : participationSettings);
    // The group limit is a fixed system policy and is intentionally not configurable.
  }

  public boolean hasReachedSuccessfulBuzzLimit(int successfulBuzzCount) {
    return successfulBuzzCount >= GLOBAL_SUCCESSFUL_BUZZ_LIMIT;
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
