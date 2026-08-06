package com.classroom.api.buzz;

import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import com.classroom.api.data.model.BuzzRecordEntity;
import com.classroom.api.data.model.SubmissionEntity;
import com.classroom.api.data.repository.BuzzRepository;
import com.classroom.api.data.repository.SubmissionRepository;
import com.classroom.api.redis.RedisBuzzLockService;
import com.classroom.api.realtime.RealtimeEventBroker;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BuzzDeadlineService implements AutoCloseable {
  static final int SWEEP_BATCH_SIZE = 100;
  static final long SWEEP_DELAY_MILLISECONDS = 500L;
  public static final int REDIS_CLEANUP_GRACE_SECONDS = 60;

  private final Database database;
  private final BuzzRepository buzzRepository;
  private final SubmissionRepository submissionRepository;
  private final RedisBuzzLockService redisBuzzLockService;
  private final RealtimeEventBroker eventBroker;
  private final ScheduledExecutorService scheduler;
  private final AtomicBoolean started = new AtomicBoolean(false);

  public BuzzDeadlineService(
      Database database,
      BuzzRepository buzzRepository,
      SubmissionRepository submissionRepository,
      RedisBuzzLockService redisBuzzLockService,
      RealtimeEventBroker eventBroker
  ) {
    this.database = database;
    this.buzzRepository = buzzRepository;
    this.submissionRepository = submissionRepository;
    this.redisBuzzLockService = redisBuzzLockService;
    this.eventBroker = eventBroker;
    this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
      Thread thread = new Thread(runnable, "buzz-deadline-sweeper");
      thread.setDaemon(true);
      return thread;
    });
  }

  public void start() {
    if (!started.compareAndSet(false, true)) {
      return;
    }
    try {
      sweepNow();
      scheduler.scheduleWithFixedDelay(
          this::sweepSafely,
          SWEEP_DELAY_MILLISECONDS,
          SWEEP_DELAY_MILLISECONDS,
          TimeUnit.MILLISECONDS
      );
    } catch (RuntimeException exception) {
      started.set(false);
      throw exception;
    }
  }

  public int sweepNow() {
    int expiredCount = 0;
    while (true) {
      var candidates = buzzRepository.listOverdueWinnerKeys(SWEEP_BATCH_SIZE);
      if (candidates.isEmpty()) {
        return expiredCount;
      }
      int expiredThisPass = 0;
      for (BuzzRepository.WinnerKey candidate : candidates) {
        if (expireOverdueWinner(candidate.questionId(), candidate.roundNumber())) {
          expiredCount++;
          expiredThisPass++;
        }
      }
      if (candidates.size() < SWEEP_BATCH_SIZE || expiredThisPass == 0) {
        return expiredCount;
      }
    }
  }

  public boolean expireOverdueWinner(UUID questionId, int roundNumber) {
    ExpiredWinner expiredWinner = null;
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      try {
        LockedWinner winner = lockActiveWinner(connection, questionId, roundNumber, true);
        if (winner != null
            && "BUZZ_OPEN".equals(winner.questionState())
            && winner.answerDeadlineAt() != null
            && !currentDatabaseTime(connection).isBefore(winner.answerDeadlineAt())
            && !hasSubmission(connection, questionId, winner.userId(), roundNumber)
            && markInactive(connection, winner.id(), "TIMED_OUT")) {
          expiredWinner = winner.asExpired();
        }
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to expire overdue buzz winner", exception);
    }

    if (expiredWinner == null) {
      return false;
    }
    completeExpiration(expiredWinner);
    return true;
  }

  public void saveWinnerSubmission(SubmissionEntity submission, int roundNumber) {
    ExpiredWinner expiredWinner = null;
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      try {
        LockedWinner winner = lockActiveWinner(connection, submission.questionId(), roundNumber, false);
        if (winner == null) {
          if (wasTimedOut(connection, submission.questionId(), submission.userId(), roundNumber)) {
            throw new IllegalArgumentException("Answer time limit has expired");
          }
          throw new IllegalArgumentException("No active buzz winner");
        }
        if (!"BUZZ_OPEN".equals(winner.questionState())) {
          throw new IllegalArgumentException("Question is not accepting answers");
        }
        if (!winner.userId().equals(submission.userId())) {
          throw new SecurityException("Only the buzz winner can submit an answer right now");
        }
        if (hasSubmission(connection, submission.questionId(), submission.userId(), roundNumber)) {
          throw new IllegalArgumentException("Duplicate submission is not allowed");
        }

        Instant databaseNow = currentDatabaseTime(connection);
        if (winner.answerDeadlineAt() != null && !databaseNow.isBefore(winner.answerDeadlineAt())) {
          if (markInactive(connection, winner.id(), "TIMED_OUT")) {
            expiredWinner = winner.asExpired();
          }
        } else {
          submissionRepository.save(connection, submission, roundNumber);
        }
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to persist winner submission", exception);
    }

    if (expiredWinner != null) {
      completeExpiration(expiredWinner);
      throw new IllegalArgumentException("Answer time limit has expired");
    }
  }

  public BuzzRecordEntity cancelWinner(UUID questionId, int roundNumber, UUID userId) {
    LockedWinner cancelledWinner;
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      try {
        cancelledWinner = lockActiveWinner(connection, questionId, roundNumber, false);
        if (cancelledWinner == null) {
          throw new IllegalArgumentException("No active buzz winner");
        }
        if (!"BUZZ_OPEN".equals(cancelledWinner.questionState())) {
          throw new IllegalArgumentException("Buzz window is closed");
        }
        if (!cancelledWinner.userId().equals(userId)) {
          throw new SecurityException("Only the active winner can cancel the buzz lock");
        }
        if (hasSubmission(connection, questionId, userId, roundNumber)) {
          throw new IllegalArgumentException("Submitted answers cannot be cancelled");
        }
        if (!markInactive(connection, cancelledWinner.id(), "CANCELLED")) {
          throw new IllegalArgumentException("No active buzz winner");
        }
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to cancel active buzz winner", exception);
    }

    deleteWinnerLock(cancelledWinner.questionId(), cancelledWinner.roundNumber(), cancelledWinner.id());
    return cancelledWinner.asEntity();
  }

  private LockedWinner lockActiveWinner(
      Connection connection,
      UUID questionId,
      int roundNumber,
      boolean skipLocked
  )
      throws SQLException {
    String sql = """
        SELECT br.id, br.classroom_id, br.question_id, br.user_id, br.request_id,
               br.receipt_order, br.received_at, br.outcome_status, br.successful,
               br.answer_deadline_at, br.round_number, q.state,
               classroom.classroom_code, winner_user.external_id, winner_user.display_name
        FROM questions q
        JOIN question_buzz_rounds qbr
          ON qbr.question_id = q.id AND qbr.current_round = ?
        JOIN buzz_records br
          ON br.question_id = q.id
         AND br.round_number = qbr.current_round
         AND br.successful = TRUE
         AND br.active = TRUE
        JOIN classrooms classroom ON classroom.id = br.classroom_id
        JOIN users winner_user ON winner_user.id = br.user_id
        WHERE q.id = ?
        ORDER BY br.receipt_order ASC, br.received_at ASC
        LIMIT 1
        FOR UPDATE OF q, br
        """ + (skipLocked ? " SKIP LOCKED" : "");
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, roundNumber);
      statement.setObject(2, questionId);
      ResultSet resultSet = statement.executeQuery();
      if (!resultSet.next()) {
        return null;
      }
      return new LockedWinner(
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
              ? null : resultSet.getTimestamp("answer_deadline_at").toInstant(),
          resultSet.getInt("round_number"),
          resultSet.getString("state"),
          resultSet.getString("classroom_code"),
          resultSet.getString("external_id"),
          resultSet.getString("display_name")
      );
    }
  }

  private static boolean hasSubmission(
      Connection connection,
      UUID questionId,
      UUID userId,
      int roundNumber
  ) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT 1 FROM submissions
        WHERE question_id = ? AND user_id = ? AND round_number = ?
        LIMIT 1
        """)) {
      statement.setObject(1, questionId);
      statement.setObject(2, userId);
      statement.setInt(3, roundNumber);
      return statement.executeQuery().next();
    }
  }

  private static boolean wasTimedOut(
      Connection connection,
      UUID questionId,
      UUID userId,
      int roundNumber
  ) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT 1 FROM buzz_records
        WHERE question_id = ? AND user_id = ? AND round_number = ?
          AND outcome_status = 'TIMED_OUT'
        LIMIT 1
        """)) {
      statement.setObject(1, questionId);
      statement.setObject(2, userId);
      statement.setInt(3, roundNumber);
      return statement.executeQuery().next();
    }
  }

  private static Instant currentDatabaseTime(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("SELECT clock_timestamp()")) {
      ResultSet resultSet = statement.executeQuery();
      resultSet.next();
      return resultSet.getTimestamp(1).toInstant();
    }
  }

  private static boolean markInactive(Connection connection, UUID winnerId, String outcomeStatus)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        UPDATE buzz_records
        SET active = FALSE, successful = FALSE, outcome_status = ?
        WHERE id = ? AND active = TRUE AND successful = TRUE
        """)) {
      statement.setString(1, outcomeStatus);
      statement.setObject(2, winnerId);
      return statement.executeUpdate() == 1;
    }
  }

  private void completeExpiration(ExpiredWinner winner) {
    deleteWinnerLock(winner.questionId(), winner.roundNumber(), winner.id());
    if (!isCurrentOpenRound(winner.questionId(), winner.roundNumber())) {
      return;
    }
    eventBroker.publish(winner.classroomCode(), "BUZZ_CANCELLED", Map.of(
        "packageId", winner.questionId().toString(),
        "questionId", winner.questionId().toString(),
        "roundId", Integer.toString(winner.roundNumber()),
        "userId", winner.userId().toString(),
        "externalUserId", winner.externalUserId(),
        "displayName", winner.displayName(),
        "reason", "TIMEOUT",
        "answerDeadlineAt", winner.answerDeadlineAt().toString(),
        "state", "BUZZ_OPEN"
    ));
  }

  private boolean isCurrentOpenRound(UUID questionId, int roundNumber) {
    String sql = """
        SELECT 1
        FROM questions q
        JOIN question_buzz_rounds qbr ON qbr.question_id = q.id
        WHERE q.id = ? AND q.state = 'BUZZ_OPEN' AND qbr.current_round = ?
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, questionId);
      statement.setInt(2, roundNumber);
      return statement.executeQuery().next();
    } catch (SQLException exception) {
      System.err.println("Unable to verify buzz lifecycle before timeout event: " + exception.getMessage());
      return false;
    }
  }

  private void deleteWinnerLock(UUID questionId, int roundNumber, UUID winnerId) {
    try {
      redisBuzzLockService.deleteIfValue(winnerKey(questionId, roundNumber), winnerId.toString());
    } catch (RuntimeException exception) {
      System.err.println("Unable to conditionally release Redis buzz winner lock: " + exception.getMessage());
    }
  }

  private void sweepSafely() {
    try {
      sweepNow();
    } catch (RuntimeException exception) {
      System.err.println("Buzz deadline sweep failed: " + exception.getMessage());
      exception.printStackTrace();
    }
  }

  private static String winnerKey(UUID questionId, int roundNumber) {
    return "buzz:winner:" + questionId + ":" + roundNumber;
  }

  private static void rollback(Connection connection, Exception original) {
    try {
      connection.rollback();
    } catch (SQLException rollbackException) {
      original.addSuppressed(rollbackException);
    }
  }

  @Override
  public void close() {
    if (started.getAndSet(false)) {
      scheduler.shutdownNow();
    }
  }

  private record LockedWinner(
      UUID id,
      UUID classroomId,
      UUID questionId,
      UUID userId,
      String requestId,
      long receiptOrder,
      Instant receivedAt,
      String outcomeStatus,
      boolean successful,
      Instant answerDeadlineAt,
      int roundNumber,
      String questionState,
      String classroomCode,
      String externalUserId,
      String displayName
  ) {
    BuzzRecordEntity asEntity() {
      return new BuzzRecordEntity(
          id, classroomId, questionId, userId, requestId, receiptOrder, receivedAt,
          outcomeStatus, successful, answerDeadlineAt);
    }

    ExpiredWinner asExpired() {
      return new ExpiredWinner(
          id, questionId, userId, roundNumber, classroomCode, externalUserId,
          displayName, answerDeadlineAt);
    }
  }

  private record ExpiredWinner(
      UUID id,
      UUID questionId,
      UUID userId,
      int roundNumber,
      String classroomCode,
      String externalUserId,
      String displayName,
      Instant answerDeadlineAt
  ) {
  }
}
