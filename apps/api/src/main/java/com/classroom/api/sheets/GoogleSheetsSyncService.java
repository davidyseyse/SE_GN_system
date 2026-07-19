package com.classroom.api.sheets;

import com.classroom.api.config.ServerConfig;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

public final class GoogleSheetsSyncService {
  private final String bearerToken;
  private final HttpClient httpClient = HttpClient.newHttpClient();

  public GoogleSheetsSyncService(ServerConfig config) {
    this.bearerToken = System.getenv("GOOGLE_SHEETS_BEARER_TOKEN");
  }

  public void updateCell(String sheetId, String targetCell, String value) {
    if (bearerToken == null || bearerToken.isBlank()) {
      return;
    }

    String encodedRange = URLEncoder.encode(targetCell, StandardCharsets.UTF_8);
    String url = "https://sheets.googleapis.com/v4/spreadsheets/" + sheetId + "/values/" + encodedRange + "?valueInputOption=USER_ENTERED";
    String body = "{\"range\":\"" + escape(targetCell) + "\",\"majorDimension\":\"ROWS\",\"values\":[[\"" + escape(value) + "\"]]}";
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .header("Authorization", "Bearer " + bearerToken)
        .header("Content-Type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofString(body))
        .build();

    try {
      HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() >= 400) {
        throw new IllegalStateException("Google Sheets update failed: " + response.body());
      }
    } catch (IOException | InterruptedException exception) {
      throw new IllegalStateException("Unable to sync answer to Google Sheets", exception);
    }
  }

  private static String escape(String value) {
    return value
        .replace("\\", "\\\\")
        .replace("\"", "\\\"");
  }
}
