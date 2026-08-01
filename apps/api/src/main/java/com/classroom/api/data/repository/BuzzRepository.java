package com.classroom.api.data.repository;

import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import com.classroom.api.data.SqlTime;
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
    save(record, 0);
  }

  public void save(BuzzRecordEntity record, int roundNumber) {
    String sql = """
        INSERT INTO buzz_records (id, classroom_id, question_id, user_id, request_id, receipt_order, received_at, outcome_status, successful, round_number, active)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)
        """;

    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, record.id());
      statement.setObject(2, record.classroomId());
      statement.setObject(3, record.questionId());
      statement.setObject(4, record.userId());
      statement.setString(5, record.requestId());
      statement.setLong(6, record.receiptOrder());
      statement.setTimestamp(7, SqlTime.from(record.receivedAt()));
      statement.setString(8, record.outcomeStatus());
      statement.setBoolean(9, record.successful());
      statement.setInt(10, roundNumber);
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to save buzz record", exception);
    }
  }

  public BuzzRecordEntity findActiveWinner(UUID questionId, int roundNumber) {
    return findByQuestionId(questionId).stream()
        .filter(BuzzRecordEntity::successful)
        .filter(record -> isActive(record.id(), roundNumber))
        .findFirst()
        .orElse(null);
  }

  private boolean isActive(UUID recordId, int roundNumber) {
    String sql = "SELECT 1 FROM buzz_records WHERE id = ? AND round_number = ? AND active = TRUE";
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, recordId);
      statement.setInt(2, roundNumber);
      return statement.executeQuery().next();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to check active buzz winner", exception);
    }
  }

  public void cancelActiveWinner(UUID questionId, int roundNumber) {
    String sql = """
        UPDATE buzz_records
        SET active = FALSE, successful = FALSE, outcome_status = 'CANCELLED'
        WHERE question_id = ? AND round_number = ? AND successful = TRUE AND active = TRUE
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, questionId);
      statement.setInt(2, roundNumber);
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to cancel active buzz winner", exception);
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
