package com.classroom.api.sheets;

import com.classroom.api.config.ServerConfig;
import com.classroom.api.util.Base64Url;
import com.classroom.api.util.JsonMapParser;
import com.classroom.api.util.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class GoogleSheetsSyncService {
  private static final String SHEETS_SCOPE = "https://www.googleapis.com/auth/spreadsheets";
  private static final long TOKEN_REFRESH_MARGIN_SECONDS = 60;
  private final HttpClient httpClient = HttpClient.newHttpClient();
  private final ServiceAccountCredentials credentials;
  private String accessToken;
  private Instant accessTokenExpiresAt = Instant.EPOCH;

  public GoogleSheetsSyncService(ServerConfig config) {
    this.credentials = loadCredentials();
  }

  public boolean updateCell(String sheetId, String targetCell, String value) {
    if (credentials == null) {
      return false;
    }
    String encodedRange = URLEncoder.encode(targetCell, StandardCharsets.UTF_8).replace("+", "%20");
    String url = "https://sheets.googleapis.com/v4/spreadsheets/" + sheetId + "/values/" + encodedRange
        + "?valueInputOption=USER_ENTERED";
    String body = "{\"range\":\"" + escape(targetCell) + "\",\"majorDimension\":\"ROWS\",\"values\":[[\""
        + escape(value) + "\"]]}";
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .header("Authorization", "Bearer " + getAccessToken())
        .header("Content-Type", "application/json; charset=utf-8")
        .PUT(HttpRequest.BodyPublishers.ofString(body)).build();
    HttpResponse<String> response = send(request, "Unable to sync answer to Google Sheets");
    if (response.statusCode() >= 400) {
      throw new IllegalStateException("Google Sheets update failed (HTTP " + response.statusCode() + "): " + response.body());
    }
    return true;
  }

  public boolean updateCells(String sheetId, Map<String, String> valuesByCell) {
    if (credentials == null || valuesByCell.isEmpty()) {
      return false;
    }
    StringBuilder data = new StringBuilder();
    for (Map.Entry<String, String> entry : valuesByCell.entrySet()) {
      if (!data.isEmpty()) data.append(',');
      data.append("{\"range\":\"").append(escape(entry.getKey()))
          .append("\",\"majorDimension\":\"ROWS\",\"values\":[[\"")
          .append(escape(entry.getValue())).append("\"]]}");
    }
    String url = "https://sheets.googleapis.com/v4/spreadsheets/" + sheetId
        + "/values:batchUpdate";
    String body = "{\"valueInputOption\":\"USER_ENTERED\",\"data\":[" + data + "]}";
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .header("Authorization", "Bearer " + getAccessToken())
        .header("Content-Type", "application/json; charset=utf-8")
        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    HttpResponse<String> response = send(request, "Unable to sync package answers to Google Sheets");
    if (response.statusCode() >= 400) {
      throw new IllegalStateException(
          "Google Sheets batch update failed (HTTP " + response.statusCode() + "): " + response.body());
    }
    return true;
  }

  public List<String> listWorksheets(String sheetId) {
    requireCredentials();
    String url = "https://sheets.googleapis.com/v4/spreadsheets/" + urlEncode(sheetId)
        + "?fields=sheets.properties.title";
    HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url))
        .header("Authorization", "Bearer " + getAccessToken()).GET().build(),
        "Unable to list Google Sheets worksheets");
    requireSuccess(response, "Google Sheets worksheet lookup failed");
    Map<String, Object> payload = JsonParser.parseObject(response.body());
    List<String> titles = new ArrayList<>();
    Object sheets = payload.get("sheets");
    if (sheets instanceof List<?> values) {
      for (Object value : values) {
        if (value instanceof Map<?, ?> sheet && sheet.get("properties") instanceof Map<?, ?> properties) {
          Object title = properties.get("title");
          if (title != null) titles.add(title.toString());
        }
      }
    }
    return List.copyOf(titles);
  }

  public List<List<String>> readWorksheet(String sheetId, String worksheet) {
    requireCredentials();
    String range = "'" + worksheet.replace("'", "''") + "'";
    String url = "https://sheets.googleapis.com/v4/spreadsheets/" + urlEncode(sheetId)
        + "/values/" + urlEncode(range) + "?majorDimension=ROWS&valueRenderOption=FORMATTED_VALUE";
    HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url))
        .header("Authorization", "Bearer " + getAccessToken()).GET().build(),
        "Unable to read Google Sheets worksheet");
    requireSuccess(response, "Google Sheets worksheet read failed");
    Object values = JsonParser.parseObject(response.body()).get("values");
    List<List<String>> rows = new ArrayList<>();
    if (values instanceof List<?> rawRows) {
      for (Object rawRow : rawRows) {
        List<String> row = new ArrayList<>();
        if (rawRow instanceof List<?> cells) {
          for (Object cell : cells) row.add(cell == null ? "" : cell.toString());
        }
        rows.add(List.copyOf(row));
      }
    }
    return List.copyOf(rows);
  }

  public void verifyWritable(String sheetId) {
    requireCredentials();
    String url = "https://sheets.googleapis.com/v4/spreadsheets/" + urlEncode(sheetId) + ":batchUpdate";
    String sentinel = "__CLASSROOM_WRITE_CHECK_" + java.util.UUID.randomUUID() + "__";
    String body = "{\"requests\":[{\"findReplace\":{\"find\":\"" + sentinel
        + "\",\"replacement\":\"" + sentinel + "\",\"allSheets\":true}}]}";
    HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(url))
        .header("Authorization", "Bearer " + getAccessToken())
        .header("Content-Type", "application/json; charset=utf-8")
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .build(), "Unable to verify Google Sheets write access");
    requireSuccess(response, "Google Sheets destination is not writable");
  }

  private void requireCredentials() {
    if (credentials == null) throw new IllegalStateException("Google service account credentials are not configured");
  }

  private static void requireSuccess(HttpResponse<String> response, String message) {
    if (response.statusCode() >= 400) {
      throw new IllegalStateException(message + " (HTTP " + response.statusCode() + "): " + response.body());
    }
  }

  public SheetGroupStatistics.Result readGroupStatistics(String sheetId) {
    if (credentials == null) {
      throw new IllegalStateException("Google service account credentials are not configured");
    }
    String url = "https://sheets.googleapis.com/v4/spreadsheets/" + urlEncode(sheetId)
        + "?includeGridData=true&fields=sheets(data(rowData(values(formattedValue))))";
    HttpRequest request = HttpRequest.newBuilder(URI.create(url))
        .header("Authorization", "Bearer " + getAccessToken())
        .GET().build();
    HttpResponse<String> response = send(request, "Unable to read Google Sheets group statistics");
    if (response.statusCode() >= 400) {
      throw new IllegalStateException(
          "Google Sheets read failed (HTTP " + response.statusCode() + "): " + response.body());
    }
    return SheetGroupStatistics.fromSpreadsheetPayload(JsonParser.parse(response.body()));
  }

  private synchronized String getAccessToken() {
    if (accessToken != null && Instant.now().plusSeconds(TOKEN_REFRESH_MARGIN_SECONDS).isBefore(accessTokenExpiresAt)) {
      return accessToken;
    }
    long issuedAt = Instant.now().getEpochSecond();
    String header = Base64Url.encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
    String claims = "{\"iss\":\"" + escape(credentials.clientEmail()) + "\",\"scope\":\"" + SHEETS_SCOPE
        + "\",\"aud\":\"" + escape(credentials.tokenUri()) + "\",\"iat\":" + issuedAt + ",\"exp\":"
        + (issuedAt + 3600) + "}";
    String unsignedJwt = header + "." + Base64Url.encode(claims.getBytes(StandardCharsets.UTF_8));
    String assertion = unsignedJwt + "." + Base64Url.encode(sign(unsignedJwt));
    String requestBody = "grant_type=" + urlEncode("urn:ietf:params:oauth:grant-type:jwt-bearer")
        + "&assertion=" + urlEncode(assertion);
    HttpRequest request = HttpRequest.newBuilder(URI.create(credentials.tokenUri()))
        .header("Content-Type", "application/x-www-form-urlencoded")
        .POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();
    HttpResponse<String> response = send(request, "Unable to obtain Google OAuth access token");
    if (response.statusCode() >= 400) {
      throw new IllegalStateException("Google OAuth token request failed (HTTP " + response.statusCode() + "): " + response.body());
    }
    Map<String, String> tokenResponse = JsonMapParser.parseObject(response.body());
    accessToken = tokenResponse.get("access_token");
    if (accessToken == null || accessToken.isBlank()) {
      throw new IllegalStateException("Google OAuth response did not include an access_token");
    }
    accessTokenExpiresAt = Instant.now().plusSeconds(Long.parseLong(tokenResponse.getOrDefault("expires_in", "3600")));
    return accessToken;
  }

  private byte[] sign(String value) {
    try {
      Signature signature = Signature.getInstance("SHA256withRSA");
      signature.initSign(credentials.privateKey());
      signature.update(value.getBytes(StandardCharsets.UTF_8));
      return signature.sign();
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to sign Google OAuth assertion", exception);
    }
  }

  private HttpResponse<String> send(HttpRequest request, String errorMessage) {
    try {
      return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(errorMessage, exception);
    } catch (IOException exception) {
      throw new IllegalStateException(errorMessage, exception);
    }
  }

  private static ServiceAccountCredentials loadCredentials() {
    String configuredPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
    Path path = configuredPath == null || configuredPath.isBlank() ? findUpward("credentials.json")
        : Path.of(configuredPath).toAbsolutePath().normalize();
    if (path == null || !Files.isRegularFile(path)) {
      return null;
    }
    try {
      Map<String, String> json = JsonMapParser.parseObject(Files.readString(path, StandardCharsets.UTF_8));
      String clientEmail = required(json, "client_email", path);
      String tokenUri = required(json, "token_uri", path);
      String keyBody = required(json, "private_key", path).replace("-----BEGIN PRIVATE KEY-----", "")
          .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
      PrivateKey privateKey = KeyFactory.getInstance("RSA")
          .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyBody)));
      return new ServiceAccountCredentials(clientEmail, tokenUri, privateKey);
    } catch (Exception exception) {
      throw new IllegalStateException("Unable to load Google service account credentials from " + path, exception);
    }
  }

  private static Path findUpward(String fileName) {
    Path cursor = Path.of("").toAbsolutePath().normalize();
    while (cursor != null) {
      Path candidate = cursor.resolve(fileName);
      if (Files.isRegularFile(candidate)) return candidate;
      cursor = cursor.getParent();
    }
    return null;
  }

  private static String required(Map<String, String> values, String key, Path path) {
    String value = values.get(key);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(key + " is missing from " + path);
    return value;
  }

  private static String urlEncode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
  }

  private record ServiceAccountCredentials(String clientEmail, String tokenUri, PrivateKey privateKey) {
  }
}
