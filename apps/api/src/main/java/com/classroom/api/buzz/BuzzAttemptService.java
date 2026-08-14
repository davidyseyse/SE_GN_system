package com.classroom.api.buzz;

import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import com.classroom.api.data.model.BuzzRecordEntity;
import com.classroom.api.data.model.SubmissionEntity;
import com.classroom.api.data.repository.ClassroomRepository;
import com.classroom.api.data.repository.SubmissionRepository;
import com.classroom.api.realtime.RealtimeEventBroker;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class BuzzAttemptService {
  private final Database database;
  private final SubmissionRepository submissionRepository;
  private final ClassroomRepository classroomRepository;
  private final RealtimeEventBroker eventBroker;

  public BuzzAttemptService(
      Database database,
      SubmissionRepository submissionRepository,
      ClassroomRepository classroomRepository,
      RealtimeEventBroker eventBroker
  ) {
    this.database = database;
    this.submissionRepository = submissionRepository;
    this.classroomRepository = classroomRepository;
    this.eventBroker = eventBroker;
  }

  public SubmissionResult saveFastestSubmission(
      SubmissionEntity submission,
      int roundNumber,
      int successfulBuzzLimit
  ) {
    AttemptView acceptedAttempt = null;
    Integer groupSuccessfulBuzzCount = null;
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      try {
        if (winnerSubmissionExists(connection, submission.questionId(), roundNumber)) {
          connection.rollback();
          return SubmissionResult.skipped("WINNER_ALREADY_SUBMITTED");
        }

        AttemptView fastest = findFastestActiveAttempt(connection, submission.questionId(), roundNumber);
        if (fastest == null) {
          connection.rollback();
          return SubmissionResult.skipped("NO_ACTIVE_ATTEMPT");
        }
        if (!fastest.userId().equals(submission.userId())) {
          connection.rollback();
          return SubmissionResult.skipped("NOT_FASTEST");
        }

        Integer groupNumber = submission.groupNumber();
        if (groupNumber == null || groupNumber <= 0) {
          connection.rollback();
          return SubmissionResult.skipped("GROUP_REQUIRED");
        }
        groupSuccessfulBuzzCount = classroomRepository.incrementGlobalGroupSuccessfulBuzzCount(
            connection, groupNumber, successfulBuzzLimit);
        if (groupSuccessfulBuzzCount == null) {
          markUserAttemptsLimitReached(
              connection, submission.questionId(), roundNumber, submission.userId());
          connection.commit();
          return SubmissionResult.skipped("SUCCESS_LIMIT_REACHED");
        }

        if (!submissionRepository.saveBuzzWinner(connection, submission, roundNumber)) {
          connection.rollback();
          return SubmissionResult.skipped("WINNER_ALREADY_SUBMITTED");
        }
        markAttempt(connection, fastest.id(), true, "WINNER", true);
        connection.commit();
        acceptedAttempt = fastest;
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to persist fastest buzz submission", exception);
    }

    publishWinner(acceptedAttempt);
    return new SubmissionResult(true, "ACCEPTED", groupSuccessfulBuzzCount == null ? 0 : groupSuccessfulBuzzCount);
  }

  public BuzzRecordEntity cancelAttempt(UUID questionId, int roundNumber, UUID userId) {
    AttemptView cancelled;
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      try {
        cancelled = findUserActiveAttempt(connection, questionId, roundNumber, userId);
        if (cancelled == null) {
          connection.rollback();
          throw new IllegalArgumentException("No active buzz attempt");
        }
        markAttempt(connection, cancelled.id(), false, "CANCELLED", false);
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to cancel buzz attempt", exception);
    }
    return cancelled.asEntity("CANCELLED", false);
  }

  private static AttemptView findFastestActiveAttempt(
      Connection connection,
      UUID questionId,
      int roundNumber
  ) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT br.id, br.classroom_id, br.question_id, br.user_id, br.request_id,
               br.receipt_order, br.received_at, br.outcome_status, br.successful,
               br.round_number, c.classroom_code, u.external_id, u.display_name
        FROM buzz_records br
        JOIN classrooms c ON c.id = br.classroom_id
        JOIN users u ON u.id = br.user_id
        JOIN questions q ON q.id = br.question_id
        JOIN question_buzz_rounds qbr
          ON qbr.question_id = br.question_id AND qbr.current_round = br.round_number
        WHERE br.question_id = ? AND br.round_number = ?
          AND br.active = TRUE AND br.successful = FALSE
          AND q.state = 'BUZZ_OPEN'
        ORDER BY br.receipt_order ASC, br.received_at ASC, br.id ASC
        LIMIT 1
        """)) {
      statement.setObject(1, questionId);
      statement.setInt(2, roundNumber);
      ResultSet resultSet = statement.executeQuery();
      return resultSet.next() ? mapAttempt(resultSet) : null;
    }
  }

  private static AttemptView findUserActiveAttempt(
      Connection connection,
      UUID questionId,
      int roundNumber,
      UUID userId
  ) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT br.id, br.classroom_id, br.question_id, br.user_id, br.request_id,
               br.receipt_order, br.received_at, br.outcome_status, br.successful,
               br.round_number, c.classroom_code, u.external_id, u.display_name
        FROM buzz_records br
        JOIN classrooms c ON c.id = br.classroom_id
        JOIN users u ON u.id = br.user_id
        WHERE br.question_id = ? AND br.round_number = ? AND br.user_id = ?
          AND br.active = TRUE AND br.successful = FALSE
        ORDER BY br.receipt_order ASC, br.received_at ASC
        LIMIT 1
        """)) {
      statement.setObject(1, questionId);
      statement.setInt(2, roundNumber);
      statement.setObject(3, userId);
      ResultSet resultSet = statement.executeQuery();
      return resultSet.next() ? mapAttempt(resultSet) : null;
    }
  }

  private static boolean winnerSubmissionExists(Connection connection, UUID questionId, int roundNumber)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT 1 FROM submissions
        WHERE question_id = ? AND round_number = ? AND buzz_winner = TRUE
        LIMIT 1
        """)) {
      statement.setObject(1, questionId);
      statement.setInt(2, roundNumber);
      return statement.executeQuery().next();
    }
  }

  private static void markAttempt(
      Connection connection,
      UUID attemptId,
      boolean successful,
      String outcomeStatus,
      boolean active
  ) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        UPDATE buzz_records SET successful = ?, outcome_status = ?, active = ? WHERE id = ?
        """)) {
      statement.setBoolean(1, successful);
      statement.setString(2, outcomeStatus);
      statement.setBoolean(3, active);
      statement.setObject(4, attemptId);
      statement.executeUpdate();
    }
  }

  private static void markUserAttemptsLimitReached(
      Connection connection,
      UUID questionId,
      int roundNumber,
      UUID userId
  ) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        UPDATE buzz_records
        SET successful = FALSE, outcome_status = 'SUCCESS_LIMIT_REACHED', active = FALSE
        WHERE question_id = ? AND round_number = ? AND user_id = ?
          AND active = TRUE AND successful = FALSE
        """)) {
      statement.setObject(1, questionId);
      statement.setInt(2, roundNumber);
      statement.setObject(3, userId);
      statement.executeUpdate();
    }
  }

  private void publishWinner(AttemptView attempt) {
    eventBroker.publish(attempt.classroomCode(), "BUZZ_RESULT", Map.of(
        "packageId", attempt.questionId().toString(),
        "questionId", attempt.questionId().toString(),
        "roundId", Integer.toString(attempt.roundNumber()),
        "userId", attempt.userId().toString(),
        "externalUserId", attempt.externalUserId(),
        "displayName", attempt.displayName(),
        "requestId", attempt.requestId(),
        "receiptOrder", attempt.receiptOrder(),
        "successful", true));
  }

  private static AttemptView mapAttempt(ResultSet resultSet) throws SQLException {
    return new AttemptView(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("classroom_id", UUID.class),
        resultSet.getObject("question_id", UUID.class),
        resultSet.getObject("user_id", UUID.class),
        resultSet.getString("request_id"),
        resultSet.getLong("receipt_order"),
        resultSet.getTimestamp("received_at").toInstant(),
        resultSet.getString("outcome_status"),
        resultSet.getBoolean("successful"),
        resultSet.getInt("round_number"),
        resultSet.getString("classroom_code"),
        resultSet.getString("external_id"),
        resultSet.getString("display_name"));
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  public record SubmissionResult(boolean accepted, String outcomeStatus, int groupSuccessfulBuzzCount) {
    static SubmissionResult skipped(String outcomeStatus) {
      return new SubmissionResult(false, outcomeStatus, 0);
    }
  }

  private record AttemptView(
      UUID id,
      UUID classroomId,
      UUID questionId,
      UUID userId,
      String requestId,
      long receiptOrder,
      Instant receivedAt,
      String outcomeStatus,
      boolean successful,
      int roundNumber,
      String classroomCode,
      String externalUserId,
      String displayName
  ) {
    BuzzRecordEntity asEntity(String status, boolean wasSuccessful) {
      return new BuzzRecordEntity(
          id, classroomId, questionId, userId, requestId, receiptOrder, receivedAt,
          status, wasSuccessful, null);
    }
  }
}
