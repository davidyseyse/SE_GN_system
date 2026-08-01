package com.classroom.api.question;

import com.classroom.api.contract.QuestionState;
import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import com.classroom.api.data.model.QuestionEntity;
import com.classroom.api.data.model.QuestionItemEntity;
import com.classroom.api.data.repository.QuestionItemRepository;
import com.classroom.api.data.repository.SheetConfigRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class QuestionPackageService {
  public static final int MAX_ITEMS = 50;
  private final Database database;
  private final QuestionItemRepository itemRepository;
  private final SheetConfigRepository sheetConfigRepository;

  public QuestionPackageService(
      Database database,
      QuestionItemRepository itemRepository,
      SheetConfigRepository sheetConfigRepository
  ) {
    this.database = database;
    this.itemRepository = itemRepository;
    this.sheetConfigRepository = sheetConfigRepository;
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

  public record CreatedPackage(
      QuestionEntity packageEntity,
      UUID sheetConfigId,
      List<QuestionItemEntity> items
  ) {
  }
}
