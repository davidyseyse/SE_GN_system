package com.classroom.api.question;

import com.classroom.api.auth.AuthContext;
import com.classroom.api.auth.AuthorizationGuard;
import com.classroom.api.contract.Role;
import com.classroom.api.data.model.ClassroomEntity;
import com.classroom.api.data.model.SheetConfigEntity;
import com.classroom.api.data.model.UserEntity;
import com.classroom.api.data.repository.ClassroomRepository;
import com.classroom.api.data.repository.QuestionItemSheetBindingRepository;
import com.classroom.api.data.repository.SheetConfigRepository;
import com.classroom.api.data.repository.UserRepository;
import com.classroom.api.http.HttpExchangeAdapter;
import com.classroom.api.http.HttpHandlerFunction;
import com.classroom.api.http.HttpResponses;
import com.classroom.api.sheets.GoogleSheetsSyncService;
import com.classroom.api.util.JsonParser;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class GeneratedQuestionController {
  private static final long TOKEN_SECONDS = 600;
  private final AuthorizationGuard authorizationGuard;
  private final ClassroomRepository classroomRepository;
  private final UserRepository userRepository;
  private final SheetConfigRepository sheetConfigRepository;
  private final QuestionItemSheetBindingRepository bindingRepository;
  private final GoogleSheetsSyncService sheetsService;
  private final QuestionPackageService packageService;
  private final SheetMarkerScanner scanner = new SheetMarkerScanner();
  private final Map<String, ScanSnapshot> scans = new ConcurrentHashMap<>();

  public GeneratedQuestionController(AuthorizationGuard authorizationGuard,
      ClassroomRepository classroomRepository, UserRepository userRepository,
      SheetConfigRepository sheetConfigRepository, QuestionItemSheetBindingRepository bindingRepository,
      GoogleSheetsSyncService sheetsService, QuestionPackageService packageService) {
    this.authorizationGuard = authorizationGuard;
    this.classroomRepository = classroomRepository;
    this.userRepository = userRepository;
    this.sheetConfigRepository = sheetConfigRepository;
    this.bindingRepository = bindingRepository;
    this.sheetsService = sheetsService;
    this.packageService = packageService;
  }

  public HttpHandlerFunction scanHandler() {
    return exchange -> {
      AuthContext auth = authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, Object> request = parseBody(exchange);
      Selection selection = selection(request);
      ClassroomEntity classroom = requireClassroom(selection.classroomCode());
      SheetConfigEntity source = requireConfig(selection.sourceConfigId());
      SheetConfigEntity answer = requireConfig(selection.answerConfigId());
      requireWorksheet(source, selection.sourceWorksheet());
      requireWorksheet(answer, selection.answerWorksheet());
      sheetsService.verifyWritable(answer.sheetId());
      SheetMarkerScanner.ScanResult result = scanner.scan(
          sheetsService.readWorksheet(source.sheetId(), selection.sourceWorksheet()));
      List<List<String>> answerRows = sheetsService.readWorksheet(answer.sheetId(), selection.answerWorksheet());
      List<Map<String, Object>> packages = preview(classroom.id(), selection, source, answer, result, answerRows);
      String token = UUID.randomUUID().toString();
      scans.put(token, new ScanSnapshot(auth.subject(), Instant.now().plusSeconds(TOKEN_SECONDS), selection,
          signature(result), result));
      HttpResponses.json(exchange, 200, Map.of("scanToken", token, "packages", packages,
          "warnings", result.warnings(), "errors", result.errors()));
    };
  }

  public HttpHandlerFunction confirmHandler() {
    return exchange -> {
      AuthContext auth = authorizationGuard.requireRoles(exchange, Role.TEACHER, Role.ASSISTANT);
      Map<String, Object> request = parseBody(exchange);
      String token = stringValue(request, "scanToken");
      ScanSnapshot snapshot = scans.get(token);
      if (snapshot == null || snapshot.expiresAt().isBefore(Instant.now())
          || !snapshot.subject().equals(auth.subject())) {
        throw new IllegalArgumentException("Scan token is invalid or expired");
      }
      Selection selection = snapshot.selection();
      ClassroomEntity classroom = requireClassroom(selection.classroomCode());
      UserEntity actor = userRepository.findByExternalId(auth.subject());
      if (actor == null) throw new IllegalArgumentException("User not found");
      SheetConfigEntity source = requireConfig(selection.sourceConfigId());
      SheetConfigEntity answer = requireConfig(selection.answerConfigId());
      requireWorksheet(source, selection.sourceWorksheet());
      requireWorksheet(answer, selection.answerWorksheet());
      sheetsService.verifyWritable(answer.sheetId());
      SheetMarkerScanner.ScanResult current = scanner.scan(
          sheetsService.readWorksheet(source.sheetId(), selection.sourceWorksheet()));
      if (!snapshot.signature().equals(signature(current))) {
        throw new IllegalArgumentException("Source worksheet changed after scanning; scan again");
      }
      if (!current.errors().isEmpty()) throw new IllegalArgumentException("Scan contains blocking marker errors");
      List<Map<String, Object>> currentPreview = preview(classroom.id(), selection, source, answer, current,
          sheetsService.readWorksheet(answer.sheetId(), selection.answerWorksheet()));
      boolean hasConflict = currentPreview.stream().flatMap(value -> ((List<?>) value.get("items")).stream())
          .filter(Map.class::isInstance).map(Map.class::cast)
          .anyMatch(value -> value.get("conflicts") instanceof List<?> conflicts && !conflicts.isEmpty());
      if (hasConflict) throw new IllegalArgumentException("Scan contains source or answer binding conflicts");
      List<QuestionPackageService.GeneratedPackageDraft> selected = parseSelectedPackages(
          request.get("packages"), snapshot.result());
      for (QuestionPackageService.GeneratedPackageDraft packageDraft : selected) {
        for (QuestionPackageService.GeneratedItemDraft item : packageDraft.items()) {
          if (bindingRepository.sourceExists(classroom.id(), selection.sourceConfigId(),
              selection.sourceWorksheet(), item.sourceCell())) throw new IllegalArgumentException("Source cell already imported");
          if (bindingRepository.answerExists(classroom.id(), selection.answerConfigId(),
              selection.answerWorksheet(), item.targetCell())) throw new IllegalArgumentException("Answer cell already bound");
        }
      }
      List<QuestionPackageService.CreatedPackage> created = packageService.createGeneratedBatch(
          classroom.id(), actor.id(), selection.sourceConfigId(), selection.sourceWorksheet(),
          selection.answerConfigId(), selection.answerWorksheet(), selected);
      scans.remove(token);
      HttpResponses.json(exchange, 201, Map.of("createdCount", created.size(), "packages",
          created.stream().map(value -> Map.of("id", value.packageEntity().id().toString(),
              "title", value.packageEntity().prompt())).toList()));
    };
  }

  private List<Map<String, Object>> preview(UUID classroomId, Selection selection,
      SheetConfigEntity source, SheetConfigEntity answer, SheetMarkerScanner.ScanResult result,
      List<List<String>> answerRows) {
    List<Map<String, Object>> packages = new ArrayList<>();
    for (SheetMarkerScanner.DetectedPackage detectedPackage : result.packages()) {
      List<Map<String, Object>> items = new ArrayList<>();
      for (SheetMarkerScanner.DetectedMarker marker : detectedPackage.items()) {
        List<String> conflicts = new ArrayList<>();
        if (bindingRepository.sourceExists(classroomId, selection.sourceConfigId(),
            selection.sourceWorksheet(), marker.cell())) conflicts.add("ALREADY_IMPORTED");
        if (bindingRepository.answerExists(classroomId, selection.answerConfigId(),
            selection.answerWorksheet(), marker.cell())) conflicts.add("ANSWER_ALREADY_BOUND");
        String destinationValue = cellValue(answerRows, marker.cell());
        boolean samePhysical = source.sheetId().equals(answer.sheetId())
            && selection.sourceWorksheet().equals(selection.answerWorksheet());
        if (!destinationValue.isBlank() && !(samePhysical && destinationValue.trim().equalsIgnoreCase(marker.marker()))) {
          conflicts.add("ANSWER_CELL_OCCUPIED");
        }
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", marker.code()); item.put("prompt", marker.code());
        item.put("sourceCell", marker.cell()); item.put("targetCell", marker.cell());
        item.put("sourceMarker", marker.marker()); item.put("conflicts", conflicts);
        items.add(item);
      }
      packages.add(Map.of("code", detectedPackage.code(), "title", detectedPackage.code(),
          "selected", items.stream().allMatch(item -> ((List<?>) item.get("conflicts")).isEmpty()), "items", items));
    }
    return packages;
  }

  private static List<QuestionPackageService.GeneratedPackageDraft> parseSelectedPackages(
      Object raw, SheetMarkerScanner.ScanResult authoritative) {
    if (!(raw instanceof List<?> packages)) throw new IllegalArgumentException("packages must be an array");
    Map<String, SheetMarkerScanner.DetectedPackage> byCode = new HashMap<>();
    authoritative.packages().forEach(value -> byCode.put(value.code(), value));
    List<QuestionPackageService.GeneratedPackageDraft> selected = new ArrayList<>();
    for (Object rawPackage : packages) {
      if (!(rawPackage instanceof Map<?, ?> packageMap)) throw new IllegalArgumentException("package is invalid");
      if (Boolean.FALSE.equals(packageMap.get("selected"))) continue;
      String code = String.valueOf(packageMap.get("code"));
      SheetMarkerScanner.DetectedPackage detected = byCode.get(code);
      if (detected == null) throw new IllegalArgumentException("Unknown detected package: " + code);
      if (!(packageMap.get("items") instanceof List<?> rawItems) || rawItems.size() != detected.items().size()) {
        throw new IllegalArgumentException("Detected items do not match scan");
      }
      Map<String, SheetMarkerScanner.DetectedMarker> byCell = new HashMap<>();
      detected.items().forEach(value -> byCell.put(value.cell(), value));
      List<QuestionPackageService.GeneratedItemDraft> items = new ArrayList<>();
      for (Object rawItem : rawItems) {
        if (!(rawItem instanceof Map<?, ?> itemMap)) throw new IllegalArgumentException("item is invalid");
        String cell = String.valueOf(itemMap.get("sourceCell"));
        SheetMarkerScanner.DetectedMarker marker = byCell.get(cell);
        if (marker == null) throw new IllegalArgumentException("Unknown source cell: " + cell);
        items.add(new QuestionPackageService.GeneratedItemDraft(String.valueOf(itemMap.get("prompt")),
            cell, cell, marker.marker()));
      }
      selected.add(new QuestionPackageService.GeneratedPackageDraft(String.valueOf(packageMap.get("title")), items));
    }
    if (selected.isEmpty()) throw new IllegalArgumentException("Select at least one package");
    return selected;
  }

  private Selection selection(Map<String, Object> request) {
    return new Selection(stringValue(request, "classroomCode"), UUID.fromString(stringValue(request, "sourceSheetConfigId")),
        stringValue(request, "sourceWorksheet"), UUID.fromString(stringValue(request, "answerSheetConfigId")),
        stringValue(request, "answerWorksheet"));
  }

  private ClassroomEntity requireClassroom(String code) {
    ClassroomEntity classroom = classroomRepository.findByCode(code.toUpperCase());
    if (classroom == null) throw new IllegalArgumentException("Classroom not found");
    return classroom;
  }
  private SheetConfigEntity requireConfig(UUID id) {
    SheetConfigEntity config = sheetConfigRepository.findById(id);
    if (config == null) throw new IllegalArgumentException("Sheet config does not exist");
    return config;
  }
  private void requireWorksheet(SheetConfigEntity config, String worksheet) {
    if (!sheetsService.listWorksheets(config.sheetId()).contains(worksheet)) {
      throw new IllegalArgumentException("Worksheet does not exist: " + worksheet);
    }
  }
  private static String signature(SheetMarkerScanner.ScanResult result) {
    return result.packages().toString() + result.errors();
  }
  private static String cellValue(List<List<String>> rows, String a1) {
    int split = 0; while (split < a1.length() && Character.isLetter(a1.charAt(split))) split++;
    int column = 0; for (int i = 0; i < split; i++) column = column * 26 + Character.toUpperCase(a1.charAt(i)) - 'A' + 1;
    int row = Integer.parseInt(a1.substring(split));
    return row <= rows.size() && column <= rows.get(row - 1).size() ? rows.get(row - 1).get(column - 1) : "";
  }
  private static Map<String, Object> parseBody(HttpExchangeAdapter exchange) throws IOException {
    return JsonParser.parseObject(exchange.readRequestBody());
  }
  private static String stringValue(Map<String, Object> values, String key) {
    Object raw = values.get(key); String value = raw == null ? "" : raw.toString().trim();
    if (value.isEmpty()) throw new IllegalArgumentException(key + " is required"); return value;
  }
  private record Selection(String classroomCode, UUID sourceConfigId, String sourceWorksheet,
                           UUID answerConfigId, String answerWorksheet) {}
  private record ScanSnapshot(String subject, Instant expiresAt, Selection selection, String signature,
                              SheetMarkerScanner.ScanResult result) {}
}
