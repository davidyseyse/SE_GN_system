package com.classroom.api.data.repository;

import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public final class BuzzRoundRepository {
  private final Database database;

  public BuzzRoundRepository(Database database) {
    this.database = database;
  }

  public int current(UUID questionId) {
    String sql = "SELECT current_round FROM question_buzz_rounds WHERE question_id = ?";
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, questionId);
      ResultSet resultSet = statement.executeQuery();
      return resultSet.next() ? resultSet.getInt(1) : 0;
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to load buzz round", exception);
    }
  }

  public int startNext(UUID questionId) {
    String sql = """
        INSERT INTO question_buzz_rounds (question_id, current_round)
        VALUES (?, 1)
        ON CONFLICT (question_id) DO UPDATE
        SET current_round = question_buzz_rounds.current_round + 1, updated_at = NOW()
        RETURNING current_round
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, questionId);
      ResultSet resultSet = statement.executeQuery();
      resultSet.next();
      return resultSet.getInt(1);
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to start buzz round", exception);
    }
  }
}
