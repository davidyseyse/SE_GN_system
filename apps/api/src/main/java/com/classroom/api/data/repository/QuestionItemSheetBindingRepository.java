package com.classroom.api.data.repository;

import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import com.classroom.api.data.model.QuestionItemSheetBindingEntity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class QuestionItemSheetBindingRepository {
  private final Database database;

  public QuestionItemSheetBindingRepository(Database database) {
    this.database = database;
  }

  public void save(Connection connection, QuestionItemSheetBindingEntity binding) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO question_item_sheet_bindings
          (item_id, classroom_id, source_sheet_config_id, source_worksheet, source_cell,
           source_marker, answer_sheet_config_id, answer_worksheet, target_cell)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """)) {
      statement.setObject(1, binding.itemId());
      statement.setObject(2, binding.classroomId());
      statement.setObject(3, binding.sourceSheetConfigId());
      statement.setString(4, binding.sourceWorksheet());
      statement.setString(5, binding.sourceCell());
      statement.setString(6, binding.sourceMarker());
      statement.setObject(7, binding.answerSheetConfigId());
      statement.setString(8, binding.answerWorksheet());
      statement.setString(9, binding.targetCell());
      statement.executeUpdate();
    }
  }

  public boolean sourceExists(UUID classroomId, UUID configId, String worksheet, String cell) {
    return exists("source_sheet_config_id", "source_worksheet", "source_cell",
        classroomId, configId, worksheet, cell);
  }

  public boolean answerExists(UUID classroomId, UUID configId, String worksheet, String cell) {
    return exists("answer_sheet_config_id", "answer_worksheet", "target_cell",
        classroomId, configId, worksheet, cell);
  }

  private boolean exists(String configColumn, String worksheetColumn, String cellColumn,
      UUID classroomId, UUID configId, String worksheet, String cell) {
    String sql = "SELECT 1 FROM question_item_sheet_bindings WHERE classroom_id = ? AND "
        + configColumn + " = ? AND UPPER(BTRIM(" + worksheetColumn + ")) = UPPER(BTRIM(?)) AND "
        + "UPPER(BTRIM(" + cellColumn + ")) = UPPER(BTRIM(?))";
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, classroomId);
      statement.setObject(2, configId);
      statement.setString(3, worksheet);
      statement.setString(4, cell);
      return statement.executeQuery().next();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to inspect generated sheet bindings", exception);
    }
  }

  public Map<UUID, QuestionItemSheetBindingEntity> listByQuestionId(UUID questionId) {
    Map<UUID, QuestionItemSheetBindingEntity> results = new LinkedHashMap<>();
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement("""
             SELECT b.* FROM question_item_sheet_bindings b
             JOIN question_items qi ON qi.id = b.item_id
             WHERE qi.question_id = ? ORDER BY qi.ordinal
             """)) {
      statement.setObject(1, questionId);
      ResultSet resultSet = statement.executeQuery();
      while (resultSet.next()) {
        QuestionItemSheetBindingEntity binding = map(resultSet);
        results.put(binding.itemId(), binding);
      }
      return results;
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to list generated sheet bindings", exception);
    }
  }

  private static QuestionItemSheetBindingEntity map(ResultSet resultSet) throws SQLException {
    return new QuestionItemSheetBindingEntity(
        resultSet.getObject("item_id", UUID.class), resultSet.getObject("classroom_id", UUID.class),
        resultSet.getObject("source_sheet_config_id", UUID.class), resultSet.getString("source_worksheet"),
        resultSet.getString("source_cell"), resultSet.getString("source_marker"),
        resultSet.getObject("answer_sheet_config_id", UUID.class), resultSet.getString("answer_worksheet"),
        resultSet.getString("target_cell"));
  }
}
