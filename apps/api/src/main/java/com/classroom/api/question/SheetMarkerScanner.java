package com.classroom.api.question;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SheetMarkerScanner {
  public static final int MAX_MARKERS = 500;
  private static final Pattern MARKER = Pattern.compile(
      "^\\[\\[Q([1-9][0-9]*)(?:-([1-9][0-9]*))?\\]\\]$", Pattern.CASE_INSENSITIVE);

  public ScanResult scan(List<List<String>> rows) {
    List<DetectedMarker> detected = new ArrayList<>();
    Map<String, Integer> markerCounts = new HashMap<>();
    for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
      List<String> row = rows.get(rowIndex);
      for (int columnIndex = 0; columnIndex < row.size(); columnIndex++) {
        String raw = row.get(columnIndex) == null ? "" : row.get(columnIndex).trim();
        Matcher matcher = MARKER.matcher(raw);
        if (!matcher.matches()) continue;
        if (detected.size() >= MAX_MARKERS) {
          return new ScanResult(List.of(), List.of(), List.of("Marker limit of " + MAX_MARKERS + " exceeded"));
        }
        int packageNumber = Integer.parseInt(matcher.group(1));
        Integer itemNumber = matcher.group(2) == null ? null : Integer.parseInt(matcher.group(2));
        String code = "Q" + packageNumber + (itemNumber == null ? "" : "-" + itemNumber);
        String normalizedMarker = "[[" + code + "]]";
        detected.add(new DetectedMarker(packageNumber, itemNumber, code, normalizedMarker,
            a1(columnIndex + 1, rowIndex + 1)));
        markerCounts.merge(normalizedMarker, 1, Integer::sum);
      }
    }

    List<String> errors = new ArrayList<>();
    markerCounts.forEach((marker, count) -> {
      if (count > 1) errors.add("Duplicate marker: " + marker);
    });
    Map<Integer, List<DetectedMarker>> grouped = new LinkedHashMap<>();
    detected.stream().sorted(Comparator.comparingInt(DetectedMarker::packageNumber)
        .thenComparing(marker -> marker.itemNumber() == null ? 0 : marker.itemNumber())
        .thenComparing(DetectedMarker::cell))
        .forEach(marker -> grouped.computeIfAbsent(marker.packageNumber(), ignored -> new ArrayList<>()).add(marker));

    List<DetectedPackage> packages = new ArrayList<>();
    List<String> warnings = new ArrayList<>();
    grouped.forEach((number, markers) -> {
      boolean standalone = markers.stream().anyMatch(marker -> marker.itemNumber() == null);
      boolean children = markers.stream().anyMatch(marker -> marker.itemNumber() != null);
      if (standalone && children) {
        errors.add("Package Q" + number + " mixes standalone and subquestion markers");
        return;
      }
      if (children) {
        Set<Integer> itemNumbers = new HashSet<>();
        int max = 0;
        for (DetectedMarker marker : markers) {
          itemNumbers.add(marker.itemNumber());
          max = Math.max(max, marker.itemNumber());
        }
        for (int expected = 1; expected <= max; expected++) {
          if (!itemNumbers.contains(expected)) warnings.add("Package Q" + number + " is missing Q" + number + "-" + expected);
        }
      }
      packages.add(new DetectedPackage("Q" + number, List.copyOf(markers)));
    });
    return new ScanResult(List.copyOf(packages), List.copyOf(warnings), List.copyOf(errors));
  }

  private static String a1(int column, int row) {
    StringBuilder letters = new StringBuilder();
    int value = column;
    while (value > 0) {
      value--;
      letters.append((char) ('A' + value % 26));
      value /= 26;
    }
    return letters.reverse() + Integer.toString(row);
  }

  public record DetectedMarker(int packageNumber, Integer itemNumber, String code, String marker, String cell) {}
  public record DetectedPackage(String code, List<DetectedMarker> items) {}
  public record ScanResult(List<DetectedPackage> packages, List<String> warnings, List<String> errors) {}
}
