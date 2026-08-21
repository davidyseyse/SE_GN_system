package com.classroom.api.buzz;

import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import com.classroom.api.data.model.BuzzRecordEntity;
import com.classroom.api.data.model.SubmissionEntity;
import com.classroom.api.data.repository.ClassroomRepository;
import com.classroom.api.data.repository.SubmissionRepository;
import com.classroom.api.realtime.RealtimeEventBroker;
import com.classroom.api.sheets.SheetAnswerFormatter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

  public SubmissionResult savePendingSubmission(SubmissionEntity submission, int roundNumber) {
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      try {
        if (winnerSubmissionExists(connection, submission.questionId(), roundNumber)) {
          connection.rollback();
          return SubmissionResult.skipped("ROUND_ALREADY_FINALIZED");
        }

        AttemptView attempt = findUserActiveAttempt(
            connection, submission.questionId(), roundNumber, submission.userId());
        if (attempt == null) {
          connection.rollback();
          return SubmissionResult.skipped("NO_ACTIVE_ATTEMPT");
        }

        if (submission.groupNumber() == null || submission.groupNumber() <= 0) {
          connection.rollback();
          return SubmissionResult.skipped("GROUP_REQUIRED");
        }

        if (!submissionRepository.savePendingBuzzSubmission(connection, submission, roundNumber)
            && !pendingSubmissionExists(
                connection, submission.questionId(), roundNumber, submission.userId())) {
          connection.rollback();
          return SubmissionResult.skipped("SUBMISSION_CONFLICT");
        }
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to persist pending buzz submission", exception);
    }
    return new SubmissionResult(true, "PENDING_REVIEW", 0);
  }

  public FinalizationResult finalizeRound(UUID questionId, int roundNumber) {
    Candidate winner = null;
    int groupSuccessfulBuzzCount = 0;
    boolean newlyFinalized = false;
    RoundContext context;
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      try {
        lockCurrentTaipeiDayFinalizationPolicy(connection);
        context = lockRound(connection, questionId, roundNumber);
        Candidate existingWinner = findExistingWinner(connection, questionId, roundNumber);
        if (existingWinner != null) {
          int existingCount = classroomRepository.globalGroupSuccessfulBuzzCount(
              connection, existingWinner.eligibilityGroupNumber());
          connection.commit();
          return new FinalizationResult(
              true, false, "ALREADY_FINALIZED", existingWinner.submissionId(),
              existingWinner.attempt().userId(), existingWinner.groupNumber(), existingCount);
        }

        List<Candidate> candidates = findPendingCandidates(connection, questionId, roundNumber);
        List<Candidate> eligibleCandidates = new ArrayList<>();
        for (Candidate candidate : candidates) {
          Integer groupNumber = candidate.eligibilityGroupNumber();
          if (groupNumber == null || groupNumber <= 0) {
            markCandidateFailed(connection, candidate, "GROUP_REQUIRED");
            continue;
          }
          int currentCount = classroomRepository.globalGroupSuccessfulBuzzCount(connection, groupNumber);
          eligibleCandidates.add(candidate.withCurrentGroupSuccessCount(currentCount));
        }
        eligibleCandidates.sort(Comparator
            .comparingInt((Candidate candidate) -> candidate.currentGroupSuccessCount() == 0 ? 0 : 1)
            .thenComparingLong(candidate -> candidate.attempt().receiptOrder())
            .thenComparing(candidate -> candidate.attempt().receivedAt())
            .thenComparing(candidate -> candidate.attempt().id()));
        if (!eligibleCandidates.isEmpty()) {
          Candidate candidate = eligibleCandidates.get(0);
          int updatedCount = classroomRepository.incrementGlobalGroupSuccessfulBuzzCount(
              connection, candidate.eligibilityGroupNumber());
          markCandidateWinner(connection, candidate);
          winner = candidate;
          groupSuccessfulBuzzCount = updatedCount;
          newlyFinalized = true;
        }

        closeRemainingCandidates(connection, questionId, roundNumber,
            winner == null ? null : winner.submissionId(), winner == null ? null : winner.attempt().id());
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        rollback(connection, exception);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to finalize buzz round", exception);
    }

    if (winner != null) publishWinner(winner, groupSuccessfulBuzzCount);
    publishReviewCompleted(context, winner, groupSuccessfulBuzzCount);
    return new FinalizationResult(
        true, newlyFinalized, winner == null ? "NO_ELIGIBLE_SUBMISSION" : "FINALIZED",
        winner == null ? null : winner.submissionId(),
        winner == null ? null : winner.attempt().userId(),
        winner == null ? null : winner.groupNumber(), groupSuccessfulBuzzCount);
  }

  /** @deprecated The daily success count is no longer a hard limit. */
  @Deprecated
  public FinalizationResult finalizeRound(UUID questionId, int roundNumber, int ignoredSuccessfulBuzzLimit) {
    return finalizeRound(questionId, roundNumber);
  }

  private static void lockCurrentTaipeiDayFinalizationPolicy(Connection connection) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT pg_advisory_xact_lock(
          1179799634,
          (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Taipei')::date - DATE '2000-01-01'
        )
        """)) {
      statement.execute();
    }
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
        if (submissionExists(connection, questionId, roundNumber, userId)) {
          connection.rollback();
          throw new IllegalArgumentException("A submitted buzz attempt cannot be cancelled");
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

  private static List<Candidate> findPendingCandidates(
      Connection connection,
      UUID questionId,
      int roundNumber
  ) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT br.id, br.classroom_id, br.question_id, br.user_id, br.request_id,
               br.receipt_order, br.received_at, br.outcome_status, br.successful,
               br.round_number, c.classroom_code, u.external_id, u.display_name,
               s.id AS submission_id, s.group_number, s.group_challenged
        FROM buzz_records br
        JOIN classrooms c ON c.id = br.classroom_id
        JOIN users u ON u.id = br.user_id
        JOIN submissions s ON s.question_id = br.question_id
          AND s.user_id = br.user_id AND s.round_number = br.round_number
        WHERE br.question_id = ? AND br.round_number = ?
          AND br.active = TRUE AND br.successful = FALSE
          AND s.review_status = 'PENDING_REVIEW'
        ORDER BY br.receipt_order ASC, br.received_at ASC, br.id ASC
        FOR UPDATE OF br, s
        """)) {
      statement.setObject(1, questionId);
      statement.setInt(2, roundNumber);
      ResultSet resultSet = statement.executeQuery();
      List<Candidate> candidates = new ArrayList<>();
      while (resultSet.next()) {
        candidates.add(new Candidate(
            mapAttempt(resultSet), resultSet.getObject("submission_id", UUID.class),
            (Integer) resultSet.getObject("group_number"),
            resultSet.getBoolean("group_challenged")));
      }
      return candidates;
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

  private static boolean submissionExists(
      Connection connection,
      UUID questionId,
      int roundNumber,
      UUID userId
  ) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT 1 FROM submissions
        WHERE question_id = ? AND round_number = ? AND user_id = ?
        LIMIT 1
        """)) {
      statement.setObject(1, questionId);
      statement.setInt(2, roundNumber);
      statement.setObject(3, userId);
      return statement.executeQuery().next();
    }
  }

  private static boolean pendingSubmissionExists(
      Connection connection,
      UUID questionId,
      int roundNumber,
      UUID userId
  ) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT 1 FROM submissions
        WHERE question_id = ? AND round_number = ? AND user_id = ?
          AND review_status = 'PENDING_REVIEW' AND buzz_winner = FALSE
        LIMIT 1
        """)) {
      statement.setObject(1, questionId);
      statement.setInt(2, roundNumber);
      statement.setObject(3, userId);
      return statement.executeQuery().next();
    }
  }

  private static RoundContext lockRound(Connection connection, UUID questionId, int roundNumber)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT c.classroom_code, qbr.current_round
        FROM questions q
        JOIN classrooms c ON c.id = q.classroom_id
        JOIN question_buzz_rounds qbr ON qbr.question_id = q.id
        WHERE q.id = ?
        FOR UPDATE OF q
        """)) {
      statement.setObject(1, questionId);
      ResultSet resultSet = statement.executeQuery();
      if (!resultSet.next()) throw new IllegalArgumentException("Question round not found");
      if (resultSet.getInt("current_round") != roundNumber) {
        throw new IllegalArgumentException("Buzz round changed before review");
      }
      return new RoundContext(questionId, roundNumber, resultSet.getString("classroom_code"));
    }
  }

  private static Candidate findExistingWinner(Connection connection, UUID questionId, int roundNumber)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT br.id, br.classroom_id, br.question_id, br.user_id, br.request_id,
               br.receipt_order, br.received_at, br.outcome_status, br.successful,
               br.round_number, c.classroom_code, u.external_id, u.display_name,
               s.id AS submission_id, s.group_number, s.group_challenged
        FROM submissions s
        JOIN buzz_records br ON br.question_id = s.question_id
          AND br.user_id = s.user_id AND br.round_number = s.round_number
          AND br.successful = TRUE
        JOIN classrooms c ON c.id = br.classroom_id
        JOIN users u ON u.id = br.user_id
        WHERE s.question_id = ? AND s.round_number = ? AND s.buzz_winner = TRUE
        ORDER BY br.receipt_order ASC
        LIMIT 1
        """)) {
      statement.setObject(1, questionId);
      statement.setInt(2, roundNumber);
      ResultSet resultSet = statement.executeQuery();
      return resultSet.next()
          ? new Candidate(mapAttempt(resultSet), resultSet.getObject("submission_id", UUID.class),
              (Integer) resultSet.getObject("group_number"),
              resultSet.getBoolean("group_challenged"))
          : null;
    }
  }

  private static void markCandidateFailed(Connection connection, Candidate candidate, String outcomeStatus)
      throws SQLException {
    markAttempt(connection, candidate.attempt().id(), false, outcomeStatus, false);
    try (PreparedStatement statement = connection.prepareStatement("""
        UPDATE submissions
        SET review_status = 'BUZZ_FAILED', reviewed_at = NOW(),
            sheet_sync_status = 'NOT_REQUIRED', sheet_sync_error = NULL
        WHERE id = ? AND review_status = 'PENDING_REVIEW'
        """)) {
      statement.setObject(1, candidate.submissionId());
      statement.executeUpdate();
    }
  }

  private static void markCandidateWinner(Connection connection, Candidate candidate) throws SQLException {
    markAttempt(connection, candidate.attempt().id(), true, "WINNER", true);
    try (PreparedStatement statement = connection.prepareStatement("""
        UPDATE submissions
        SET buzz_winner = TRUE, review_status = 'BUZZ_SUCCESS', reviewed_at = NOW()
        WHERE id = ? AND review_status = 'PENDING_REVIEW'
        """)) {
      statement.setObject(1, candidate.submissionId());
      if (statement.executeUpdate() != 1) {
        throw new SQLException("Pending winner submission changed concurrently");
      }
    }
  }

  private static void closeRemainingCandidates(
      Connection connection,
      UUID questionId,
      int roundNumber,
      UUID winnerSubmissionId,
      UUID winnerAttemptId
  ) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        UPDATE submissions
        SET review_status = 'BUZZ_FAILED', reviewed_at = NOW(),
            sheet_sync_status = 'NOT_REQUIRED', sheet_sync_error = NULL
        WHERE question_id = ? AND round_number = ? AND review_status = 'PENDING_REVIEW'
          AND (?::uuid IS NULL OR id <> ?)
        """)) {
      statement.setObject(1, questionId);
      statement.setInt(2, roundNumber);
      statement.setObject(3, winnerSubmissionId);
      statement.setObject(4, winnerSubmissionId);
      statement.executeUpdate();
    }
    try (PreparedStatement statement = connection.prepareStatement("""
        UPDATE buzz_records
        SET successful = FALSE,
            outcome_status = CASE WHEN EXISTS (
              SELECT 1 FROM submissions s
              WHERE s.question_id = buzz_records.question_id
                AND s.user_id = buzz_records.user_id
                AND s.round_number = buzz_records.round_number
            ) THEN 'NOT_FASTEST' ELSE 'NO_SUBMISSION' END,
            active = FALSE
        WHERE question_id = ? AND round_number = ? AND active = TRUE AND successful = FALSE
          AND (?::uuid IS NULL OR id <> ?)
        """)) {
      statement.setObject(1, questionId);
      statement.setInt(2, roundNumber);
      statement.setObject(3, winnerAttemptId);
      statement.setObject(4, winnerAttemptId);
      statement.executeUpdate();
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

  private void publishWinner(Candidate winner, int groupSuccessfulBuzzCount) {
    AttemptView attempt = winner.attempt();
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("packageId", attempt.questionId().toString());
    payload.put("questionId", attempt.questionId().toString());
    payload.put("roundId", Integer.toString(attempt.roundNumber()));
    payload.put("userId", attempt.userId().toString());
    payload.put("externalUserId", attempt.externalUserId());
    payload.put("displayName", attempt.displayName());
    payload.put("requestId", attempt.requestId());
    payload.put("receiptOrder", attempt.receiptOrder());
    payload.put("winnerGroupNumber", winner.groupNumber() == null ? 0 : winner.groupNumber());
    payload.put("groupSuccessfulBuzzCount", groupSuccessfulBuzzCount);
    payload.put("successful", true);
    eventBroker.publish(attempt.classroomCode(), "BUZZ_RESULT", payload);
  }

  private void publishReviewCompleted(
      RoundContext context,
      Candidate winner,
      int groupSuccessfulBuzzCount
  ) {
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("packageId", context.questionId().toString());
    payload.put("questionId", context.questionId().toString());
    payload.put("roundId", Integer.toString(context.roundNumber()));
    payload.put("winnerUserId", winner == null ? "" : winner.attempt().userId().toString());
    payload.put("winnerExternalUserId", winner == null ? "" : winner.attempt().externalUserId());
    payload.put("winnerGroupNumber", winner == null || winner.groupNumber() == null ? 0 : winner.groupNumber());
    payload.put("groupSuccessfulBuzzCount", groupSuccessfulBuzzCount);
    eventBroker.publish(context.classroomCode(), "BUZZ_REVIEW_COMPLETED", payload);
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

  public record FinalizationResult(
      boolean finalized,
      boolean newlyFinalized,
      String outcomeStatus,
      UUID winnerSubmissionId,
      UUID winnerUserId,
      Integer winnerGroupNumber,
      int groupSuccessfulBuzzCount
  ) {
  }

  private record Candidate(
      AttemptView attempt,
      UUID submissionId,
      Integer groupNumber,
      boolean groupChallenged,
      int currentGroupSuccessCount
  ) {
    private Candidate(AttemptView attempt, UUID submissionId, Integer groupNumber, boolean groupChallenged) {
      this(attempt, submissionId, groupNumber, groupChallenged, 0);
    }

    private Candidate withCurrentGroupSuccessCount(int count) {
      return new Candidate(attempt, submissionId, groupNumber, groupChallenged, count);
    }

    private Integer eligibilityGroupNumber() {
      return groupChallenged
          ? SheetAnswerFormatter.extractGroupNumber(attempt.displayName())
          : groupNumber;
    }
  }

  private record RoundContext(UUID questionId, int roundNumber, String classroomCode) {
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
