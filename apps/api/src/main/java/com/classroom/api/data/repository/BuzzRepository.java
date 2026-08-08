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
        INSERT INTO buzz_records (id, classroom_id, question_id, user_id, request_id, receipt_order, received_at, outcome_status, successful, round_number, active, answer_deadline_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
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
      statement.setTimestamp(11, SqlTime.from(record.answerDeadlineAt()));
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to save buzz record", exception);
    }
  }

  public BuzzRecordEntity findActiveWinner(UUID questionId, int roundNumber) {
    String sql = """
        SELECT id, classroom_id, question_id, user_id, request_id, receipt_order,
               received_at, outcome_status, successful, answer_deadline_at
        FROM buzz_records
        WHERE question_id = ? AND round_number = ? AND successful = TRUE AND active = TRUE
        ORDER BY receipt_order ASC, received_at ASC
        LIMIT 1
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, questionId);
      statement.setInt(2, roundNumber);
      ResultSet resultSet = statement.executeQuery();
      return resultSet.next() ? mapBuzzRecord(resultSet) : null;
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to load active buzz winner", exception);
    }
  }

  public List<WinnerKey> listOverdueWinnerKeys(int limit) {
    String sql = """
        SELECT br.question_id, br.round_number
        FROM buzz_records br
        JOIN questions q ON q.id = br.question_id
        JOIN question_buzz_rounds qbr
          ON qbr.question_id = br.question_id AND qbr.current_round = br.round_number
        WHERE br.successful = TRUE
          AND br.active = TRUE
          AND br.answer_deadline_at IS NOT NULL
          AND br.answer_deadline_at <= clock_timestamp()
          AND q.state = 'BUZZ_OPEN'
          AND NOT EXISTS (
            SELECT 1
            FROM submissions s
            WHERE s.question_id = br.question_id
              AND s.user_id = br.user_id
              AND s.round_number = br.round_number
          )
        ORDER BY br.answer_deadline_at ASC
        LIMIT ?
        """;
    List<WinnerKey> results = new ArrayList<>();
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, limit);
      ResultSet resultSet = statement.executeQuery();
      while (resultSet.next()) {
        results.add(new WinnerKey(
            resultSet.getObject("question_id", UUID.class),
            resultSet.getInt("round_number")
        ));
      }
      return results;
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to list overdue buzz winners", exception);
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
        SELECT id, classroom_id, question_id, user_id, request_id, receipt_order,
               received_at, outcome_status, successful, answer_deadline_at
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
        results.add(mapBuzzRecord(resultSet));
      }
      return results;
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to load buzz records", exception);
    }
  }

  private static BuzzRecordEntity mapBuzzRecord(ResultSet resultSet) throws SQLException {
    return new BuzzRecordEntity(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("classroom_id", UUID.class),
        resultSet.getObject("question_id", UUID.class),
        resultSet.getObject("user_id", UUID.class),
        resultSet.getString("request_id"),
        resultSet.getLong("receipt_order"),
        resultSet.getTimestamp("received_at").toInstant(),
        resultSet.getString("outcome_status"),
        resultSet.getBoolean("successful"),
        resultSet.getTimestamp("answer_deadline_at") == null
            ? null : resultSet.getTimestamp("answer_deadline_at").toInstant()
    );
  }

  public record WinnerKey(UUID questionId, int roundNumber) {
  }
}
