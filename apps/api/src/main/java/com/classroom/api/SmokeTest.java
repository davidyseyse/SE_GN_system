package com.classroom.api;

import com.classroom.api.auth.AuthTokenService;
import com.classroom.api.config.ServerConfig;
import com.classroom.api.contract.Role;
import com.classroom.api.util.EnvFileLoader;
import com.classroom.api.util.JsonParser;
import com.classroom.api.question.SheetCellRange;
import com.classroom.api.sheets.SheetAnswerFormatter;
import com.classroom.api.sheets.SheetGroupStatistics;
import java.util.List;
import java.util.Map;

public final class SmokeTest {
  private SmokeTest() {
  }

  public static void main(String[] args) {
    Map<String, String> env = EnvFileLoader.load();
    String port = env.getOrDefault("API_PORT", System.getenv().getOrDefault("API_PORT", "8080"));
    ServerConfig config = ServerConfig.load();
    AuthTokenService tokenService = new AuthTokenService(config);
    String token = tokenService.issueToken("smoke-user", Role.TEACHER, "Smoke User");
    tokenService.verify(token);
    Map<String, Object> nested = JsonParser.parseObject(
        "{\"title\":\"Package\",\"items\":[{\"prompt\":\"One\",\"targetCell\":\"a1\"}]}");
    if (!(nested.get("items") instanceof List<?> items) || items.size() != 1) {
      throw new IllegalStateException("Nested JSON parsing failed");
    }
    if (!"A1".equals(SheetCellRange.normalize(" a1 "))) {
      throw new IllegalStateException("Single-cell normalization failed");
    }
    if (!"'Score Sheet'!B2".equals(SheetCellRange.normalize("'Score Sheet'!b2"))) {
      throw new IllegalStateException("Sheet-qualified cell normalization failed");
    }
    try {
      SheetCellRange.normalize("A1:B2");
      throw new IllegalStateException("Multi-cell range was not rejected");
    } catch (IllegalArgumentException expected) {
      // Expected.
    }
    if (!"1233_G6".equals(SheetAnswerFormatter.format("1233", "2 / 第6組"))) {
      throw new IllegalStateException("Google Sheets answer formatting failed");
    }
    if (!"4455_G2".equals(SheetAnswerFormatter.format("4455", "第2組"))) {
      throw new IllegalStateException("Legacy display-name answer formatting failed");
    }
    SheetGroupStatistics.Result statistics = SheetGroupStatistics.fromSpreadsheetPayload(
        JsonParser.parse("{\"sheets\":[{\"data\":[{\"rowData\":[{\"values\":["
            + "{\"formattedValue\":\"fdsfs_G1/ asda_G2\"},"
            + "{\"formattedValue\":\"again_G1 / duplicate_G1\"},"
            + "{\"formattedValue\":\"answer_G2\"},{\"formattedValue\":\"ignore_G0\"}]}]}]}]}"));
    if (statistics.successfulGroupCount() != 2 || statistics.totalSuccessfulAnswers() != 4
        || statistics.groups().get(0).groupNumber() != 1
        || statistics.groups().get(0).successfulAnswerCount() != 2) {
      throw new IllegalStateException("Google Sheets group statistics parsing failed");
    }
    System.out.println("Smoke test passed. API_PORT=" + port + ", JWT=" + token.substring(0, Math.min(token.length(), 24)) + "...");
  }
}
