package com.classroom.api.data.repository;

import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import com.classroom.api.data.model.ScoreRecordEntity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ScoreRepository {
  private final Database database;

  public ScoreRepository(Database database) {
    this.database = database;
  }

  public void save(ScoreRecordEntity scoreRecord) {
    String sql = """
        INSERT INTO score_records (id, classroom_id, user_id, question_id, source_type, delta_score, running_total, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, scoreRecord.id());
      statement.setObject(2, scoreRecord.classroomId());
      statement.setObject(3, scoreRecord.userId());
      statement.setObject(4, scoreRecord.questionId());
      statement.setString(5, scoreRecord.sourceType());
      statement.setInt(6, scoreRecord.deltaScore());
      statement.setInt(7, scoreRecord.runningTotal());
      statement.setObject(8, scoreRecord.createdAt());
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to save score record", exception);
    }
  }

  public List<ScoreRecordEntity> listByClassroom(UUID classroomId) {
    String sql = """
        SELECT id, classroom_id, user_id, question_id, source_type, delta_score, running_total, created_at
        FROM score_records
        WHERE classroom_id = ?
        ORDER BY created_at DESC
        """;
    List<ScoreRecordEntity> results = new ArrayList<>();
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, classroomId);
      ResultSet resultSet = statement.executeQuery();
      while (resultSet.next()) {
        results.add(new ScoreRecordEntity(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("classroom_id", UUID.class),
            resultSet.getObject("user_id", UUID.class),
            resultSet.getObject("question_id", UUID.class),
            resultSet.getString("source_type"),
            resultSet.getInt("delta_score"),
            resultSet.getInt("running_total"),
            resultSet.getTimestamp("created_at").toInstant()
        ));
      }
      return results;
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to list score records", exception);
    }
  }

  public int currentTotal(UUID classroomId, UUID userId) {
    String sql = """
        SELECT running_total
        FROM score_records
        WHERE classroom_id = ? AND user_id = ?
        ORDER BY created_at DESC
        LIMIT 1
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, classroomId);
      statement.setObject(2, userId);
      ResultSet resultSet = statement.executeQuery();
      if (!resultSet.next()) {
        return 0;
      }
      return resultSet.getInt("running_total");
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to read current score total", exception);
    }
  }
}
