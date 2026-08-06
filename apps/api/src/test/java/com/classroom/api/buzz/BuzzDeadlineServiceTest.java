package com.classroom.api.buzz;

import com.classroom.api.config.ServerConfig;
import com.classroom.api.data.Database;
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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class BuzzDeadlineServiceTest {
  private final ServerConfig config = ServerConfig.load();
  private final Database database = new Database(config);
  private final BuzzRepository buzzRepository = new BuzzRepository(database);
  private final SubmissionRepository submissionRepository = new SubmissionRepository(database);
  private final RedisBuzzLockService redis = new RedisBuzzLockService(config);

  public static void main(String[] args) throws Exception {
    BuzzDeadlineServiceTest test = new BuzzDeadlineServiceTest();
    test.stableDeadlineSurvivesQuestionEdit();
    test.expirationIsIdempotentAndConditionallyDeletesRedis();
    test.submissionAndTimeoutHaveOneAtomicOutcome();
    test.submissionPreventsLaterExpiration();
    test.lifecycleAndLegacyWinnersAreProtected();
    System.out.println("Buzz deadline service tests passed.");
  }

  private void stableDeadlineSurvivesQuestionEdit() throws Exception {
    try (Fixture fixture = createFixture("BUZZ_OPEN", 5)) {
      Instant receivedAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
      Instant deadline = receivedAt.plusSeconds(5);
      BuzzRecordEntity winner = fixture.saveWinner(receivedAt, deadline);

      execute("UPDATE questions SET answer_time_limit_seconds = 99 WHERE id = ?", fixture.questionId);
      BuzzRecordEntity reloaded = buzzRepository.findActiveWinner(fixture.questionId, fixture.roundNumber);

      assertNotNull(reloaded, "Stable-deadline winner was not reloaded");
      assertEquals(deadline, reloaded.answerDeadlineAt(), "Question edit changed the stored winner deadline");
      assertEquals(winner.id(), reloaded.id(), "Repository loaded the wrong active winner");
    }
  }

  private void expirationIsIdempotentAndConditionallyDeletesRedis() throws Exception {
    RealtimeEventBroker broker = new RealtimeEventBroker();
    try (Fixture fixture = createFixture("BUZZ_OPEN", 1);
         BuzzDeadlineService service = new BuzzDeadlineService(
             database, buzzRepository, submissionRepository, redis, broker)) {
      BuzzRecordEntity winner = fixture.saveWinner(Instant.now().minusSeconds(3), Instant.now().minusSeconds(2));
      UUID replacementClaim = UUID.randomUUID();
      assertTrue(redis.tryLock(fixture.redisKey(), replacementClaim.toString(), 60),
          "Could not establish replacement Redis claim for compare-delete test");

      assertTrue(service.expireOverdueWinner(fixture.questionId, fixture.roundNumber),
          "First overdue expiration did not transition the winner");
      assertFalse(service.expireOverdueWinner(fixture.questionId, fixture.roundNumber),
          "Repeated expiration transitioned the same winner twice");
      assertTrue(redis.deleteIfValue(fixture.redisKey(), replacementClaim.toString()),
          "Timeout cleanup deleted a different Redis claim");

      BuzzRecordEntity history = findRecord(winner.id());
      assertEquals("TIMED_OUT", history.outcomeStatus(), "Timed-out history outcome was not retained");
      assertFalse(history.successful(), "Timed-out winner still reports successful");
      assertEquals(1, timeoutEventCount(broker, fixture.classroomCode),
          "Idempotent expiration did not publish exactly one timeout event");
    }
  }

  private void submissionAndTimeoutHaveOneAtomicOutcome() throws Exception {
    RealtimeEventBroker broker = new RealtimeEventBroker();
    try (Fixture fixture = createFixture("BUZZ_OPEN", 1);
         BuzzDeadlineService service = new BuzzDeadlineService(
             database, buzzRepository, submissionRepository, redis, broker)) {
      BuzzRecordEntity winner = fixture.saveWinner(Instant.now().minusSeconds(3), Instant.now().minusSeconds(2));
      assertTrue(redis.tryLock(fixture.redisKey(), winner.id().toString(), 60),
          "Could not establish Redis claim for race test");
      SubmissionEntity submission = fixture.submission();
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService executor = Executors.newFixedThreadPool(2);
      List<Throwable> failures = new ArrayList<>();
      try {
        Future<?> submitFuture = executor.submit(() -> {
          await(start);
          try {
            service.saveWinnerSubmission(submission, fixture.roundNumber);
          } catch (Throwable exception) {
            synchronized (failures) {
              failures.add(exception);
            }
          }
        });
        Future<?> expireFuture = executor.submit(() -> {
          await(start);
          service.expireOverdueWinner(fixture.questionId, fixture.roundNumber);
        });
        start.countDown();
        submitFuture.get();
        expireFuture.get();
      } finally {
        executor.shutdownNow();
      }

      assertFalse(submissionRepository.hasSubmission(
          fixture.questionId, fixture.studentId, fixture.roundNumber),
          "Late submission was persisted during the timeout race");
      assertNull(buzzRepository.findActiveWinner(fixture.questionId, fixture.roundNumber),
          "Timeout race left an active winner");
      assertTrue(failures.stream().anyMatch(error ->
              error instanceof IllegalArgumentException
                  && error.getMessage().contains("time limit has expired")),
          "Late submitter did not receive the deadline rejection");
      assertEquals(1, timeoutEventCount(broker, fixture.classroomCode),
          "Concurrent timeout processing published a duplicate or missing event");
      UUID nextClaim = UUID.randomUUID();
      assertTrue(redis.tryLock(fixture.redisKey(), nextClaim.toString(), 60),
          "Timed-out race left the Redis winner lock stuck");
      redis.deleteIfValue(fixture.redisKey(), nextClaim.toString());
    }
  }

  private void submissionPreventsLaterExpiration() throws Exception {
    RealtimeEventBroker broker = new RealtimeEventBroker();
    try (Fixture fixture = createFixture("BUZZ_OPEN", 30);
         BuzzDeadlineService service = new BuzzDeadlineService(
             database, buzzRepository, submissionRepository, redis, broker)) {
      fixture.saveWinner(Instant.now(), Instant.now().plusSeconds(30));
      service.saveWinnerSubmission(fixture.submission(), fixture.roundNumber);
      execute("UPDATE buzz_records SET answer_deadline_at = clock_timestamp() - interval '1 second' "
          + "WHERE question_id = ?", fixture.questionId);

      assertFalse(service.expireOverdueWinner(fixture.questionId, fixture.roundNumber),
          "Persisted submission was later expired");
      assertNotNull(buzzRepository.findActiveWinner(fixture.questionId, fixture.roundNumber),
          "On-time submission lost its winner history");
      assertEquals(0, timeoutEventCount(broker, fixture.classroomCode),
          "On-time submission emitted a timeout event");
    }
  }

  private void lifecycleAndLegacyWinnersAreProtected() throws Exception {
    try (Fixture stopped = createFixture("LOCKED", 1);
         Fixture legacy = createFixture("BUZZ_OPEN", null);
         BuzzDeadlineService service = new BuzzDeadlineService(
             database, buzzRepository, submissionRepository, redis, new RealtimeEventBroker())) {
      stopped.saveWinner(Instant.now().minusSeconds(5), Instant.now().minusSeconds(4));
      legacy.saveWinner(Instant.now().minusSeconds(60), null);

      assertFalse(service.expireOverdueWinner(stopped.questionId, stopped.roundNumber),
          "Timeout processing changed a teacher-stopped question");
      assertFalse(service.expireOverdueWinner(legacy.questionId, legacy.roundNumber),
          "Legacy winner without a positive deadline expired");
      assertNotNull(buzzRepository.findActiveWinner(stopped.questionId, stopped.roundNumber),
          "Stopped question lost its winner");
      assertNotNull(buzzRepository.findActiveWinner(legacy.questionId, legacy.roundNumber),
          "Legacy no-timeout winner was released");
    }
  }

  private Fixture createFixture(String state, Integer answerTimeLimitSeconds) throws SQLException {
    UUID teacherId = UUID.randomUUID();
    UUID studentId = UUID.randomUUID();
    UUID classroomId = UUID.randomUUID();
    UUID questionId = UUID.randomUUID();
    String suffix = questionId.toString().replace("-", "").substring(0, 10);
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      try {
        execute(connection, "INSERT INTO users (id, external_id, display_name, role) VALUES (?, ?, ?, 'TEACHER')",
            teacherId, "teacher-" + suffix, "Deadline Teacher");
        execute(connection, "INSERT INTO users (id, external_id, display_name, role) VALUES (?, ?, ?, 'STUDENT')",
            studentId, "student-" + suffix, "Deadline Student");
        execute(connection, "INSERT INTO classrooms "
                + "(id, teacher_id, classroom_code, name, status, participation_settings) "
                + "VALUES (?, ?, ?, 'Deadline Test', 'OPEN', '{}'::jsonb)",
            classroomId, teacherId, ("T" + suffix).toUpperCase());
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO questions
              (id, classroom_id, ordinal, type, state, prompt, answer_key,
               publication_settings, score, answer_time_limit_seconds, created_by)
            VALUES (?, ?, 1, 'BUZZ', ?, 'Deadline question', '{}'::jsonb,
                    '{}'::jsonb, 0, ?, ?)
            """)) {
          statement.setObject(1, questionId);
          statement.setObject(2, classroomId);
          statement.setString(3, state);
          statement.setObject(4, answerTimeLimitSeconds);
          statement.setObject(5, teacherId);
          statement.executeUpdate();
        }
        execute(connection,
            "INSERT INTO question_buzz_rounds (question_id, current_round) VALUES (?, 1)", questionId);
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    }
    return new Fixture(teacherId, studentId, classroomId, questionId, 1,
        ("T" + suffix).toUpperCase());
  }

  private BuzzRecordEntity findRecord(UUID recordId) throws SQLException {
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement("""
             SELECT id, classroom_id, question_id, user_id, request_id, receipt_order,
                    received_at, outcome_status, successful, answer_deadline_at
             FROM buzz_records WHERE id = ?
             """)) {
      statement.setObject(1, recordId);
      ResultSet resultSet = statement.executeQuery();
      if (!resultSet.next()) {
        return null;
      }
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
  }

  private static int timeoutEventCount(RealtimeEventBroker broker, String classroomCode) {
    int count = 0;
    for (Map<String, Object> event : broker.readEvents(classroomCode, 0)) {
      if (!"BUZZ_CANCELLED".equals(event.get("type"))) {
        continue;
      }
      Object payload = event.get("payload");
      if (payload instanceof Map<?, ?> values && "TIMEOUT".equals(values.get("reason"))) {
        count++;
      }
    }
    return count;
  }

  private void execute(String sql, Object... values) throws SQLException {
    try (Connection connection = database.openConnection()) {
      execute(connection, sql, values);
    }
  }

  private static void execute(Connection connection, String sql, Object... values) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < values.length; index++) {
        statement.setObject(index + 1, values[index]);
      }
      statement.executeUpdate();
    }
  }

  private static void await(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while starting timeout race", exception);
    }
  }

  private static void assertTrue(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void assertFalse(boolean condition, String message) {
    assertTrue(!condition, message);
  }

  private static void assertNull(Object value, String message) {
    assertTrue(value == null, message);
  }

  private static void assertNotNull(Object value, String message) {
    assertTrue(value != null, message);
  }

  private static void assertEquals(Object expected, Object actual, String message) {
    if (expected == null ? actual != null : !expected.equals(actual)) {
      throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
    }
  }

  private final class Fixture implements AutoCloseable {
    private final UUID teacherId;
    private final UUID studentId;
    private final UUID classroomId;
    private final UUID questionId;
    private final int roundNumber;
    private final String classroomCode;

    private Fixture(
        UUID teacherId,
        UUID studentId,
        UUID classroomId,
        UUID questionId,
        int roundNumber,
        String classroomCode
    ) {
      this.teacherId = teacherId;
      this.studentId = studentId;
      this.classroomId = classroomId;
      this.questionId = questionId;
      this.roundNumber = roundNumber;
      this.classroomCode = classroomCode;
    }

    private BuzzRecordEntity saveWinner(Instant receivedAt, Instant deadline) {
      BuzzRecordEntity record = new BuzzRecordEntity(
          UUID.randomUUID(), classroomId, questionId, studentId, UUID.randomUUID().toString(),
          1, receivedAt, "WINNER", true, deadline);
      buzzRepository.save(record, roundNumber);
      return record;
    }

    private SubmissionEntity submission() {
      return new SubmissionEntity(
          UUID.randomUUID(), classroomId, questionId, studentId, 1, "deadline answer",
          "RECEIVED", null, null, "PENDING_REVIEW", Instant.now(), null);
    }

    private String redisKey() {
      return "buzz:winner:" + questionId + ":" + roundNumber;
    }

    @Override
    public void close() throws Exception {
      redis.delete(redisKey());
      execute("DELETE FROM classrooms WHERE id = ?", classroomId);
      execute("DELETE FROM users WHERE id IN (?, ?)", teacherId, studentId);
    }
  }
}
