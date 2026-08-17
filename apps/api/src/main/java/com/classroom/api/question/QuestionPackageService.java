package com.classroom.api.question;

import com.classroom.api.contract.QuestionState;
import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import com.classroom.api.data.model.QuestionEntity;
import com.classroom.api.data.model.QuestionItemEntity;
import com.classroom.api.data.model.QuestionItemSheetBindingEntity;
import com.classroom.api.data.repository.QuestionItemRepository;
import com.classroom.api.data.repository.QuestionItemSheetBindingRepository;
import com.classroom.api.data.repository.SheetConfigRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class QuestionPackageService {
  public static final int MAX_ITEMS = 50;
  private final Database database;
  private final QuestionItemRepository itemRepository;
  private final SheetConfigRepository sheetConfigRepository;
  private final QuestionItemSheetBindingRepository bindingRepository;

  public QuestionPackageService(
      Database database,
      QuestionItemRepository itemRepository,
      SheetConfigRepository sheetConfigRepository,
      QuestionItemSheetBindingRepository bindingRepository
  ) {
    this.database = database;
    this.itemRepository = itemRepository;
    this.sheetConfigRepository = sheetConfigRepository;
    this.bindingRepository = bindingRepository;
  }

  public CreatedPackage create(
      UUID classroomId,
      UUID actorId,
      String title,
      UUID sheetConfigId,
      List<ItemDraft> drafts,
      int score,
      Integer answerTimeLimitSeconds
  ) {
    String normalizedTitle = title == null ? "" : title.trim();
    if (normalizedTitle.isEmpty()) throw new IllegalArgumentException("title is required");
    if (sheetConfigId == null || sheetConfigRepository.findById(sheetConfigId) == null) {
      throw new IllegalArgumentException("sheetConfigId is invalid");
    }
    if (drafts == null || drafts.isEmpty()) throw new IllegalArgumentException("items must not be empty");
    if (drafts.size() > MAX_ITEMS) throw new IllegalArgumentException("items exceeds maximum of " + MAX_ITEMS);

    List<ItemDraft> normalizedDrafts = new ArrayList<>();
    Set<String> cells = new HashSet<>();
    for (int index = 0; index < drafts.size(); index++) {
      ItemDraft draft = drafts.get(index);
      String prompt = draft.prompt() == null ? "" : draft.prompt().trim();
      if (prompt.isEmpty()) throw new IllegalArgumentException("items[" + index + "].prompt is required");
      String targetCell;
      try {
        targetCell = SheetCellRange.normalize(draft.targetCell());
      } catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException("items[" + index + "]." + exception.getMessage());
      }
      if (!cells.add(targetCell.toUpperCase())) {
        throw new IllegalArgumentException("Duplicate targetCell: " + targetCell);
      }
      normalizedDrafts.add(new ItemDraft(prompt, targetCell));
    }

    UUID packageId = UUID.randomUUID();
    Instant now = Instant.now();
    QuestionEntity packageEntity = new QuestionEntity(
        packageId, classroomId, 0, "BUZZ", QuestionState.READY.name(), normalizedTitle,
        "{}", "{\"showAnswer\":false}", score, answerTimeLimitSeconds, actorId, now, now, null);
    List<QuestionItemEntity> items = new ArrayList<>();

    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      try {
        int ordinal = nextOrdinal(connection, classroomId);
        packageEntity = new QuestionEntity(
            packageEntity.id(), packageEntity.classroomId(), ordinal, packageEntity.type(),
            packageEntity.state(), packageEntity.prompt(), packageEntity.answerKey(),
            packageEntity.publicationSettings(), packageEntity.score(),
            packageEntity.answerTimeLimitSeconds(), packageEntity.createdBy(),
            packageEntity.createdAt(), packageEntity.updatedAt(), packageEntity.archivedAt());
        insertPackage(connection, packageEntity, sheetConfigId);
        for (int index = 0; index < normalizedDrafts.size(); index++) {
          ItemDraft draft = normalizedDrafts.get(index);
          QuestionItemEntity item = new QuestionItemEntity(
              UUID.randomUUID(), packageId, index + 1, draft.prompt(), draft.targetCell());
          itemRepository.save(connection, item);
          items.add(item);
        }
        connection.commit();
      } catch (Exception exception) {
        connection.rollback();
        throw exception;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to create question package", exception);
    }
    return new CreatedPackage(packageEntity, sheetConfigId, List.copyOf(items));
  }

  private static int nextOrdinal(Connection connection, UUID classroomId) throws SQLException {
    try (PreparedStatement lock = connection.prepareStatement(
        "SELECT id FROM classrooms WHERE id = ? FOR UPDATE")) {
      lock.setObject(1, classroomId);
      lock.executeQuery();
    }
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT COALESCE(MAX(ordinal), 0) + 1 FROM questions WHERE classroom_id = ?")) {
      statement.setObject(1, classroomId);
      ResultSet resultSet = statement.executeQuery();
      resultSet.next();
      return resultSet.getInt(1);
    }
  }

  public UUID findSheetConfigId(UUID packageId) {
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT sheet_config_id FROM questions WHERE id = ?")) {
      statement.setObject(1, packageId);
      ResultSet resultSet = statement.executeQuery();
      if (!resultSet.next()) return null;
      return resultSet.getObject(1, UUID.class);
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to load package sheet configuration", exception);
    }
  }

  public List<CreatedPackage> createGeneratedBatch(
      UUID classroomId,
      UUID actorId,
      UUID sourceSheetConfigId,
      String sourceWorksheet,
      UUID answerSheetConfigId,
      String answerWorksheet,
      List<GeneratedPackageDraft> packages
  ) {
    if (sheetConfigRepository.findById(sourceSheetConfigId) == null
        || sheetConfigRepository.findById(answerSheetConfigId) == null) {
      throw new IllegalArgumentException("Sheet configuration is invalid");
    }
    if (sourceWorksheet == null || sourceWorksheet.isBlank()
        || answerWorksheet == null || answerWorksheet.isBlank()) {
      throw new IllegalArgumentException("Worksheet is required");
    }
    if (packages == null || packages.isEmpty()) throw new IllegalArgumentException("packages must not be empty");
    List<CreatedPackage> created = new ArrayList<>();
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      try {
        int ordinal = nextOrdinal(connection, classroomId);
        for (GeneratedPackageDraft draft : packages) {
          String title = requiredText(draft.title(), "title");
          if (draft.items() == null || draft.items().isEmpty() || draft.items().size() > MAX_ITEMS) {
            throw new IllegalArgumentException("Generated package items are invalid");
          }
          UUID packageId = UUID.randomUUID();
          Instant now = Instant.now();
          QuestionEntity entity = new QuestionEntity(packageId, classroomId, ordinal++, "BUZZ",
              QuestionState.READY.name(), title, "{}", "{\"showAnswer\":false}", 10, 30,
              actorId, now, now, null);
          insertPackage(connection, entity, answerSheetConfigId);
          List<QuestionItemEntity> items = new ArrayList<>();
          Set<String> cells = new HashSet<>();
          for (int index = 0; index < draft.items().size(); index++) {
            GeneratedItemDraft itemDraft = draft.items().get(index);
            String prompt = requiredText(itemDraft.prompt(), "item prompt");
            String sourceCell = SheetCellRange.normalize(itemDraft.sourceCell());
            String targetCell = SheetCellRange.normalize(itemDraft.targetCell());
            if (!cells.add(targetCell.toUpperCase())) throw new IllegalArgumentException("Duplicate target cell");
            UUID itemId = UUID.randomUUID();
            QuestionItemEntity item = new QuestionItemEntity(itemId, packageId, index + 1, prompt,
                quoteWorksheet(answerWorksheet) + "!" + targetCell);
            itemRepository.save(connection, item);
            bindingRepository.save(connection, new QuestionItemSheetBindingEntity(itemId, classroomId,
                sourceSheetConfigId, sourceWorksheet.trim(), sourceCell, itemDraft.sourceMarker(),
                answerSheetConfigId, answerWorksheet.trim(), targetCell));
            items.add(item);
          }
          created.add(new CreatedPackage(entity, answerSheetConfigId, List.copyOf(items)));
        }
        connection.commit();
      } catch (Exception exception) {
        connection.rollback();
        throw exception;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to create generated question packages", exception);
    }
    return List.copyOf(created);
  }

  public CreatedPackage updateDescriptions(UUID classroomId, UUID packageId, String title,
      List<ItemDescription> descriptions) {
    String normalizedTitle = requiredText(title, "title");
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      try {
        QuestionEntity entity;
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, classroom_id, ordinal, type, state, prompt, answer_key::text,
                   publication_settings::text, score, answer_time_limit_seconds, created_by,
                   created_at, updated_at, archived_at
            FROM questions WHERE id = ? AND classroom_id = ? FOR UPDATE
            """)) {
          statement.setObject(1, packageId);
          statement.setObject(2, classroomId);
          ResultSet rs = statement.executeQuery();
          if (!rs.next()) throw new IllegalArgumentException("Question package not found");
          if (!QuestionState.READY.name().equals(rs.getString("state"))) {
            throw new IllegalArgumentException("Only READY packages can be edited");
          }
          entity = new QuestionEntity(rs.getObject("id", UUID.class), rs.getObject("classroom_id", UUID.class),
              rs.getInt("ordinal"), rs.getString("type"), rs.getString("state"), normalizedTitle,
              rs.getString("answer_key"), rs.getString("publication_settings"), rs.getInt("score"),
              (Integer) rs.getObject("answer_time_limit_seconds"), rs.getObject("created_by", UUID.class),
              rs.getTimestamp("created_at").toInstant(), Instant.now(),
              rs.getTimestamp("archived_at") == null ? null : rs.getTimestamp("archived_at").toInstant());
        }
        List<QuestionItemEntity> authoritative = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
            SELECT id, question_id, ordinal, prompt, target_cell FROM question_items
            WHERE question_id = ? ORDER BY ordinal FOR UPDATE
            """)) {
          statement.setObject(1, packageId);
          ResultSet rs = statement.executeQuery();
          while (rs.next()) authoritative.add(new QuestionItemEntity(rs.getObject("id", UUID.class),
              rs.getObject("question_id", UUID.class), rs.getInt("ordinal"), rs.getString("prompt"),
              rs.getString("target_cell")));
        }
        Map<UUID, String> requested = new java.util.LinkedHashMap<>();
        if (descriptions != null) for (ItemDescription description : descriptions) {
          if (requested.put(description.itemId(), requiredText(description.prompt(), "item prompt")) != null) {
            throw new IllegalArgumentException("Duplicate itemId");
          }
        }
        if (requested.size() != authoritative.size()
            || authoritative.stream().anyMatch(item -> !requested.containsKey(item.id()))) {
          throw new IllegalArgumentException("items must exactly match the package");
        }
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE questions SET prompt = ?, updated_at = NOW() WHERE id = ?")) {
          statement.setString(1, normalizedTitle); statement.setObject(2, packageId); statement.executeUpdate();
        }
        List<QuestionItemEntity> updatedItems = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "UPDATE question_items SET prompt = ?, updated_at = NOW() WHERE id = ?")) {
          for (QuestionItemEntity item : authoritative) {
            String prompt = requested.get(item.id());
            statement.setString(1, prompt); statement.setObject(2, item.id()); statement.addBatch();
            updatedItems.add(new QuestionItemEntity(item.id(), item.questionId(), item.ordinal(), prompt, item.targetCell()));
          }
          statement.executeBatch();
        }
        connection.commit();
        return new CreatedPackage(entity, findSheetConfigId(packageId), List.copyOf(updatedItems));
      } catch (Exception exception) {
        connection.rollback();
        throw exception;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to update package descriptions", exception);
    }
  }

  private static String requiredText(String value, String field) {
    String normalized = value == null ? "" : value.trim();
    if (normalized.isEmpty()) throw new IllegalArgumentException(field + " is required");
    return normalized;
  }

  private static String quoteWorksheet(String worksheet) {
    return "'" + worksheet.replace("'", "''") + "'";
  }

  private static void insertPackage(Connection connection, QuestionEntity entity, UUID sheetConfigId)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO questions
          (id, classroom_id, ordinal, type, state, prompt, answer_key, publication_settings,
           score, answer_time_limit_seconds, created_by, created_at, updated_at, archived_at,
           sheet_config_id)
        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?)
        """)) {
      statement.setObject(1, entity.id());
      statement.setObject(2, entity.classroomId());
      statement.setInt(3, entity.ordinal());
      statement.setString(4, entity.type());
      statement.setString(5, entity.state());
      statement.setString(6, entity.prompt());
      statement.setString(7, entity.answerKey());
      statement.setString(8, entity.publicationSettings());
      statement.setInt(9, entity.score());
      if (entity.answerTimeLimitSeconds() == null) statement.setNull(10, java.sql.Types.INTEGER);
      else statement.setInt(10, entity.answerTimeLimitSeconds());
      statement.setObject(11, entity.createdBy());
      statement.setTimestamp(12, java.sql.Timestamp.from(entity.createdAt()));
      statement.setTimestamp(13, java.sql.Timestamp.from(entity.updatedAt()));
      statement.setNull(14, java.sql.Types.TIMESTAMP);
      statement.setObject(15, sheetConfigId);
      statement.executeUpdate();
    }
  }

  public record ItemDraft(String prompt, String targetCell) {
  }

  public record GeneratedItemDraft(String prompt, String targetCell, String sourceCell, String sourceMarker) {}
  public record GeneratedPackageDraft(String title, List<GeneratedItemDraft> items) {}
  public record ItemDescription(UUID itemId, String prompt) {}

  public record CreatedPackage(
      QuestionEntity packageEntity,
      UUID sheetConfigId,
      List<QuestionItemEntity> items
  ) {
  }
}
