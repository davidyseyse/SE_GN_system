package com.classroom.api.data.repository;

import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import com.classroom.api.data.model.QuestionItemEntity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class QuestionItemRepository {
  private final Database database;

  public QuestionItemRepository(Database database) {
    this.database = database;
  }

  public void save(Connection connection, QuestionItemEntity item) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO question_items (id, question_id, ordinal, prompt, target_cell)
        VALUES (?, ?, ?, ?, ?)
        """)) {
      statement.setObject(1, item.id());
      statement.setObject(2, item.questionId());
      statement.setInt(3, item.ordinal());
      statement.setString(4, item.prompt());
      statement.setString(5, item.targetCell());
      statement.executeUpdate();
    }
  }

  public List<QuestionItemEntity> listByQuestionId(UUID questionId) {
    String sql = """
        SELECT id, question_id, ordinal, prompt, target_cell
        FROM question_items
        WHERE question_id = ?
        ORDER BY ordinal
        """;
    List<QuestionItemEntity> results = new ArrayList<>();
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, questionId);
      ResultSet resultSet = statement.executeQuery();
      while (resultSet.next()) {
        results.add(map(resultSet));
      }
      return results;
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to list question package items", exception);
    }
  }

  public boolean isItemId(UUID itemId) {
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT 1 FROM question_items WHERE id = ?")) {
      statement.setObject(1, itemId);
      return statement.executeQuery().next();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to identify question item", exception);
    }
  }

  private static QuestionItemEntity map(ResultSet resultSet) throws SQLException {
    return new QuestionItemEntity(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("question_id", UUID.class),
        resultSet.getInt("ordinal"),
        resultSet.getString("prompt"),
        resultSet.getString("target_cell")
    );
  }
}
