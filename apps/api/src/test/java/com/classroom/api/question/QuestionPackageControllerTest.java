package com.classroom.api.question;

import com.classroom.api.util.JsonParser;
import java.util.Map;

public final class QuestionPackageControllerTest {
  private QuestionPackageControllerTest() {
  }

  public static void main(String[] args) {
    Map<String, Object> parsed = JsonParser.parseObject("{\"answerTimeLimitSeconds\":30}");
    assertEquals(
        30,
        QuestionPackageController.positiveIntegerValue(
            parsed.get("answerTimeLimitSeconds"), "answerTimeLimitSeconds"),
        "JSON whole number was rejected"
    );
    assertEquals(
        30,
        QuestionPackageController.positiveIntegerValue(30.0d, "answerTimeLimitSeconds"),
        "Whole-valued JSON number was rejected"
    );

    assertRejected(1.5d);
    assertRejected(0d);
    assertRejected(-1L);
    assertRejected("abc");
    assertRejected(null);
    System.out.println("Question package answer-limit validation tests passed.");
  }

  private static void assertRejected(Object value) {
    try {
      QuestionPackageController.positiveIntegerValue(value, "answerTimeLimitSeconds");
      throw new AssertionError("Invalid answer limit was accepted: " + value);
    } catch (IllegalArgumentException expected) {
      // Expected.
    }
  }

  private static void assertEquals(int expected, int actual, String message) {
    if (expected != actual) {
      throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
    }
  }
}
