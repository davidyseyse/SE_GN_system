package com.classroom.api.sheets;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SheetGroupStatistics {
  private static final Pattern GROUP_SUFFIX = Pattern.compile("_G([1-9]\\d*)\\s*$");

  private SheetGroupStatistics() {
  }

  public static Result fromSpreadsheetPayload(Object payload) {
    TreeMap<Integer, Integer> counts = new TreeMap<>();
    visit(payload, counts);
    List<GroupScore> groups = new ArrayList<>();
    int total = 0;
    for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
      groups.add(new GroupScore(entry.getKey(), entry.getValue()));
      total += entry.getValue();
    }
    return new Result(List.copyOf(groups), groups.size(), total);
  }

  private static void visit(Object value, Map<Integer, Integer> counts) {
    if (value instanceof Map<?, ?> map) {
      Object formattedValue = map.get("formattedValue");
      if (formattedValue instanceof String text) {
        HashSet<Integer> cellGroups = new HashSet<>();
        for (String answer : text.split("[/／]")) {
          Matcher matcher = GROUP_SUFFIX.matcher(answer);
          if (matcher.find()) {
            cellGroups.add(Integer.parseInt(matcher.group(1)));
          }
        }
        for (int groupNumber : cellGroups) {
          counts.merge(groupNumber, 1, Integer::sum);
        }
      }
      for (Object nested : map.values()) {
        visit(nested, counts);
      }
    } else if (value instanceof List<?> list) {
      for (Object nested : list) {
        visit(nested, counts);
      }
    }
  }

  public record GroupScore(int groupNumber, int successfulAnswerCount) {
  }

  public record Result(List<GroupScore> groups, int successfulGroupCount, int totalSuccessfulAnswers) {
  }
}
