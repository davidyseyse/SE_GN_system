package com.classroom.api.question;

import java.util.List;

public final class SheetMarkerScannerTest {
  private SheetMarkerScannerTest() {}

  public static void main(String[] args) {
    SheetMarkerScanner scanner = new SheetMarkerScanner();
    var result = scanner.scan(List.of(
        List.of("image", " [[q1-1]] ", "Q1-2"),
        List.of("", "[[Q1-3]]", "[[Q2]]")));
    check(result.errors().isEmpty(), "valid markers produced errors");
    check(result.packages().size() == 2, "package grouping failed");
    check(result.packages().get(0).items().get(0).cell().equals("B1"), "A1 mapping failed");
    check(result.packages().get(0).items().get(0).code().equals("Q1-1"), "normalization failed");
    check(result.warnings().stream().anyMatch(value -> value.contains("Q1-2")), "gap warning missing");
    check(result.packages().get(1).items().get(0).code().equals("Q2"), "standalone marker failed");

    var duplicate = scanner.scan(List.of(List.of("[[Q1]]", "[[q1]]")));
    check(duplicate.errors().stream().anyMatch(value -> value.contains("Duplicate")), "duplicate not rejected");

    var mixed = scanner.scan(List.of(List.of("[[Q1]]", "[[Q1-1]]")));
    check(mixed.errors().stream().anyMatch(value -> value.contains("mixes")), "mixed package not rejected");

    var invalid = scanner.scan(List.of(List.of("[[Q0]]", "[[Q1-0]]", "text [[Q3]]", "=IMAGE(x)")));
    check(invalid.packages().isEmpty(), "invalid or image content was detected");
    System.out.println("Sheet marker scanner tests passed.");
  }

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }
}
