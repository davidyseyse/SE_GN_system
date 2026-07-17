package com.classroom.api.data.repository;

import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import com.classroom.api.data.model.BuzzRecordEntity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class BuzzRepository {
  private final Database database;

  public BuzzRepository(Database database) {
    this.database = database;
  }

  public void save(BuzzRecordEntity record) {
    String sql = """
        INSERT INTO buzz_records (id, classroom_id, question_id, user_id, request_id, receipt_order, received_at, outcome_status, successful)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, record.id());
      statement.setObject(2, record.classroomId());
      statement.setObject(3, record.questionId());
      statement.setObject(4, record.userId());
      statement.setString(5, record.requestId());
      statement.setLong(6, record.receiptOrder());
      statement.setObject(7, record.receivedAt());
      statement.setString(8, record.outcomeStatus());
      statement.setBoolean(9, record.successful());
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to save buzz record", exception);
    }
  }

  public List<BuzzRecordEntity> findByQuestionId(UUID questionId) {
    String sql = """
        SELECT id, classroom_id, question_id, user_id, request_id, receipt_order, received_at, outcome_status, successful
        FROM buzz_records
        WHERE question_id = ?
        ORDER BY receipt_order ASC, received_at ASC
        """;
    List<BuzzRecordEntity> results = new ArrayList<>();

    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, questionId);
      ResultSet resultSet = statement.executeQuery();
      while (resultSet.next()) {
        results.add(new BuzzRecordEntity(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("classroom_id", UUID.class),
            resultSet.getObject("question_id", UUID.class),
            resultSet.getObject("user_id", UUID.class),
            resultSet.getString("request_id"),
            resultSet.getLong("receipt_order"),
            resultSet.getTimestamp("received_at").toInstant(),
            resultSet.getString("outcome_status"),
            resultSet.getBoolean("successful")
        ));
      }
      return results;
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to load buzz records", exception);
    }
  }
}
