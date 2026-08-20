package com.classroom.api.buzz;

import com.classroom.api.config.ServerConfig;
import com.classroom.api.data.Database;
import com.classroom.api.data.model.BuzzRecordEntity;
import com.classroom.api.data.model.SubmissionEntity;
import com.classroom.api.data.repository.BuzzRepository;
import com.classroom.api.data.repository.ClassroomRepository;
import com.classroom.api.data.repository.SubmissionRepository;
import com.classroom.api.realtime.RealtimeEventBroker;
import com.classroom.api.score.ClassroomScoringPolicy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class BuzzAttemptServiceTest {
  private final Database database = new Database(ServerConfig.load());
  private final BuzzRepository buzzRepository = new BuzzRepository(database);
  private final SubmissionRepository submissionRepository = new SubmissionRepository(database);
  private final ClassroomRepository classroomRepository = new ClassroomRepository(database);

  public static void main(String[] args) throws Exception {
    BuzzAttemptServiceTest test = new BuzzAttemptServiceTest();
    test.pendingSubmissionRetryIsIdempotent();
    test.submissionsStayPendingUntilFastestEligibleIsFinalized();
    test.successLimitSkipsEarlierGroupDuringFinalization();
    test.challengedGroupRegainsEligibility();
    test.previousTaipeiDaySuccessDoesNotBlockToday();
    test.legacyDeadlineDoesNotExpireAnAttempt();
    test.explicitCancellationAdvancesEligibility();
    test.concurrentWinnerCommitCountsExactlyOnce();
    test.globalGroupFinalSlotIsAtomicAcrossClassrooms();
    test.sameGroupAcrossClassroomsAllowsOnlyOneWinner();
    test.classroomSettingCannotOverrideFixedGlobalLimit();
    System.out.println("Open buzz ranking, no-deadline, and success-limit tests passed.");
  }

  private void pendingSubmissionRetryIsIdempotent() throws Exception {
    try (Fixture fixture = createFixture()) {
      BuzzAttemptService service = service();
      fixture.saveAttempt(fixture.studentOneId, 1, null);

      var first = service.savePendingSubmission(fixture.submission(fixture.studentOneId), 1);
      var retry = service.savePendingSubmission(fixture.submission(fixture.studentOneId), 1);

      assertTrue(first.accepted(), "Initial pending submission was rejected");
      assertTrue(retry.accepted(), "Pending submission retry was reported as a buzz failure");
      assertEquals("PENDING_REVIEW", retry.outcomeStatus(), "Retry did not preserve pending outcome");
      assertEquals(1, submissionCount(fixture.questionId, fixture.studentOneId),
          "Pending retry created more than one submission row");
    }
  }

  private void submissionsStayPendingUntilFastestEligibleIsFinalized() throws Exception {
    try (Fixture fixture = createFixture()) {
      BuzzAttemptService service = service();
      fixture.saveAttempt(fixture.studentOneId, 1, null);
      fixture.saveAttempt(fixture.studentTwoId, 2, null);

      var slower = service.savePendingSubmission(fixture.submission(fixture.studentTwoId), 1);
      var fastest = service.savePendingSubmission(fixture.submission(fixture.studentOneId), 1);
      assertTrue(slower.accepted(), "Slower student's answer was not stored as pending");
      assertTrue(fastest.accepted(), "Fastest student's answer was not stored as pending");
      assertEquals("PENDING_REVIEW", slower.outcomeStatus(), "Submission revealed a result before review");
      assertEquals(0, winnerSubmissionCount(fixture.questionId), "A winner existed before teacher review");
      assertEquals(0, classroomRepository.globalGroupSuccessfulBuzzCount(fixture.groupOne),
          "Pending submission incremented the group count");

      var finalized = service.finalizeRound(fixture.questionId, 1, 1);
      assertEquals(fixture.studentOneId, finalized.winnerUserId(), "Fastest eligible attempt did not win");
      assertEquals(1, finalized.groupSuccessfulBuzzCount(), "Finalization did not increment winner count");
      assertTrue(submissionRepository.hasSubmission(fixture.questionId, fixture.studentOneId, 1),
          "Accepted fastest submission was not persisted");
      assertEquals(1, winnerSubmissionCount(fixture.questionId), "Round persisted more than one winner");
      assertEquals("BUZZ_SUCCESS", submissionReviewStatus(fixture.questionId, fixture.studentOneId),
          "Winner submission did not expose the reviewed success status");
      assertEquals("BUZZ_FAILED", submissionReviewStatus(fixture.questionId, fixture.studentTwoId),
          "Non-winner submission did not expose the reviewed failure status");
    }
  }

  private void successLimitSkipsEarlierGroupDuringFinalization() throws Exception {
    try (Fixture fixture = createFixture()) {
      BuzzAttemptService service = service();
      execute("INSERT INTO global_group_buzz_counts (group_number, successful_buzz_count) VALUES (?, 1)",
          fixture.groupOne);
      fixture.saveAttempt(fixture.studentOneId, 1, null);
      fixture.saveAttempt(fixture.studentTwoId, 2, null);
      assertTrue(service.savePendingSubmission(fixture.submission(fixture.studentOneId), 1).accepted(),
          "Over-limit group learned the result during submission");
      assertTrue(service.savePendingSubmission(fixture.submission(fixture.studentTwoId), 1).accepted(),
          "Later eligible submission was not saved");

      var result = service.finalizeRound(fixture.questionId, 1, 1);
      assertEquals(fixture.studentTwoId, result.winnerUserId(),
          "Finalization did not skip the earlier over-limit group");
      assertEquals(1, classroomRepository.globalGroupSuccessfulBuzzCount(fixture.groupOne),
          "Limit rejection changed the group success count");
      assertEquals("SUCCESS_LIMIT_REACHED", attemptOutcome(fixture.questionId, fixture.studentOneId),
          "Over-limit attempt was not made ineligible");
      assertEquals("BUZZ_FAILED", submissionReviewStatus(fixture.questionId, fixture.studentOneId),
          "Over-limit pending answer did not become a generic student-visible failure");
    }
  }

  private void previousTaipeiDaySuccessDoesNotBlockToday() throws Exception {
    try (Fixture fixture = createFixture()) {
      BuzzAttemptService service = service();
      execute("""
          INSERT INTO global_group_buzz_counts
            (group_number, success_date, successful_buzz_count)
          VALUES (?, (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Taipei')::date - 1, 1)
          """, fixture.groupOne);
      fixture.saveAttempt(fixture.studentOneId, 1, null);

      assertEquals(0, classroomRepository.globalGroupSuccessfulBuzzCount(fixture.groupOne),
          "Previous-day success leaked into today's group count");
      assertTrue(service.savePendingSubmission(fixture.submission(fixture.studentOneId), 1).accepted(),
          "Previous-day success blocked pending submission");
      var result = service.finalizeRound(fixture.questionId, 1, 1);
      assertEquals(fixture.studentOneId, result.winnerUserId(), "Previous-day success blocked today's first winner");
      assertEquals(1, classroomRepository.globalGroupSuccessfulBuzzCount(fixture.groupOne),
          "Today's accepted answer did not create a new daily count");
    }
  }

  private void legacyDeadlineDoesNotExpireAnAttempt() throws Exception {
    try (Fixture fixture = createFixture()) {
      BuzzAttemptService service = service();
      fixture.saveAttempt(fixture.studentOneId, 1, Instant.now().minusSeconds(60));

      assertTrue(service.savePendingSubmission(fixture.submission(fixture.studentOneId), 1).accepted(),
          "A legacy deadline incorrectly expired the pending submission");
      var result = service.finalizeRound(fixture.questionId, 1, 1);
      assertEquals(fixture.studentOneId, result.winnerUserId(), "A legacy deadline incorrectly blocked finalization");
      assertEquals("WINNER", attemptOutcome(fixture.questionId, fixture.studentOneId),
          "No-deadline winner was not recorded");
    }
  }

  private void explicitCancellationAdvancesEligibility() throws Exception {
    try (Fixture fixture = createFixture()) {
      BuzzAttemptService service = service();
      fixture.saveAttempt(fixture.studentOneId, 1, null);
      fixture.saveAttempt(fixture.studentTwoId, 2, null);

      service.cancelAttempt(fixture.questionId, 1, fixture.studentOneId);
      assertEquals("CANCELLED", attemptOutcome(fixture.questionId, fixture.studentOneId),
          "Explicit cancellation retained the wrong outcome");

      assertTrue(service.savePendingSubmission(fixture.submission(fixture.studentTwoId), 1).accepted(),
          "Next active attempt could not submit after cancellation");
      var result = service.finalizeRound(fixture.questionId, 1, 1);
      assertEquals(fixture.studentTwoId, result.winnerUserId(),
          "Next active attempt did not become eligible after cancellation");
    }
  }

  private void concurrentWinnerCommitCountsExactlyOnce() throws Exception {
    try (Fixture fixture = createFixture()) {
      BuzzAttemptService service = service();
      fixture.saveAttempt(fixture.studentOneId, 1, null);
      service.savePendingSubmission(fixture.submission(fixture.studentOneId), 1);
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        Future<BuzzAttemptService.FinalizationResult> first = executor.submit(() -> {
          start.await();
          return service.finalizeRound(fixture.questionId, 1, 1);
        });
        Future<BuzzAttemptService.FinalizationResult> second = executor.submit(() -> {
          start.await();
          return service.finalizeRound(fixture.questionId, 1, 1);
        });
        start.countDown();
        int finalized = (first.get().newlyFinalized() ? 1 : 0) + (second.get().newlyFinalized() ? 1 : 0);
        assertEquals(1, finalized, "Concurrent reviews finalized more than once");
      } finally {
        executor.shutdownNow();
      }
      assertEquals(1, winnerSubmissionCount(fixture.questionId), "Winner uniqueness invariant failed");
      assertEquals(1, classroomRepository.globalGroupSuccessfulBuzzCount(fixture.groupOne),
          "Concurrent winner commit incremented the group success count more than once");
    }
  }

  private void globalGroupFinalSlotIsAtomicAcrossClassrooms() throws Exception {
    try (Fixture fixture = createFixture()) {
      int isolatedGroup = fixture.groupOne;
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        Future<Integer> first = executor.submit(() -> incrementGroupAfter(start, isolatedGroup, 1));
        Future<Integer> second = executor.submit(() -> incrementGroupAfter(start, isolatedGroup, 1));
        start.countDown();
        int accepted = (first.get() == null ? 0 : 1) + (second.get() == null ? 0 : 1);
        assertEquals(1, accepted, "Same-group concurrent successes exceeded the final slot");
      } finally {
        executor.shutdownNow();
      }
      assertEquals(1, classroomRepository.globalGroupSuccessfulBuzzCount(isolatedGroup),
          "Cross-classroom group accounting exceeded the fixed global limit");
    }
  }

  private void classroomSettingCannotOverrideFixedGlobalLimit() {
    ClassroomScoringPolicy policy = ClassroomScoringPolicy.fromSettings(
        "{\"successfulBuzzLimit\":99,\"buzzBonus\":5}");
    assertEquals(1, policy.successfulBuzzLimit(),
        "Classroom settings overrode the fixed global group limit");
  }

  private void sameGroupAcrossClassroomsAllowsOnlyOneWinner() throws Exception {
    try (Fixture firstClassroom = createFixture();
         Fixture secondClassroom = createFixture(firstClassroom.groupOne)) {
      BuzzAttemptService service = service();
      firstClassroom.saveAttempt(firstClassroom.studentOneId, 1, null);
      secondClassroom.saveAttempt(secondClassroom.studentOneId, 1, null);
      service.savePendingSubmission(firstClassroom.submission(firstClassroom.studentOneId), 1);
      service.savePendingSubmission(secondClassroom.submission(secondClassroom.studentOneId), 1);
      CountDownLatch start = new CountDownLatch(1);
      ExecutorService executor = Executors.newFixedThreadPool(2);
      try {
        Future<BuzzAttemptService.FinalizationResult> first = executor.submit(() -> {
          start.await();
          return service.finalizeRound(firstClassroom.questionId, 1, 1);
        });
        Future<BuzzAttemptService.FinalizationResult> second = executor.submit(() -> {
          start.await();
          return service.finalizeRound(secondClassroom.questionId, 1, 1);
        });
        start.countDown();
        int winners = (first.get().winnerSubmissionId() == null ? 0 : 1)
            + (second.get().winnerSubmissionId() == null ? 0 : 1);
        assertEquals(1, winners, "The same group won in two different classrooms");
      } finally {
        executor.shutdownNow();
      }
      int winners = winnerSubmissionCount(firstClassroom.questionId)
          + winnerSubmissionCount(secondClassroom.questionId);
      assertEquals(1, winners, "Cross-classroom submissions exceeded the global group limit");
      assertEquals(1, classroomRepository.globalGroupSuccessfulBuzzCount(firstClassroom.groupOne),
          "Cross-classroom success count was not global");
    }
  }

  private Integer incrementGroupAfter(
      CountDownLatch start,
      int groupNumber,
      int limit
  ) throws Exception {
    start.await();
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      Integer result = classroomRepository.incrementGlobalGroupSuccessfulBuzzCount(
          connection, groupNumber, limit);
      connection.commit();
      return result;
    }
  }

  private BuzzAttemptService service() {
    return new BuzzAttemptService(
        database, submissionRepository, classroomRepository, new RealtimeEventBroker());
  }

  private Fixture createFixture() throws SQLException {
    return createFixture(null);
  }

  private void challengedGroupRegainsEligibility() throws Exception {
    try (Fixture fixture = createFixture()) {
      BuzzAttemptService service = service();
      execute("""
          INSERT INTO global_group_buzz_counts
            (group_number, success_date, successful_buzz_count)
          VALUES (?, (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Taipei')::date, 1)
          """, fixture.groupTwo);
      fixture.saveAttempt(fixture.studentOneId, 1, null);
      SubmissionEntity pending = fixture.submission(fixture.studentOneId);
      assertTrue(service.savePendingSubmission(pending, 1).accepted(),
          "Challenge fixture submission was rejected");

      SubmissionEntity challenged = new SubmissionEntity(
          pending.id(), pending.classroomId(), pending.questionId(), pending.userId(),
          pending.attemptNumber(), pending.submittedAnswer(), pending.submissionStatus(),
          pending.isCorrect(), pending.awardedScore(), pending.reviewStatus(), pending.submittedAt(),
          pending.reviewedAt(), fixture.groupTwo, pending.enteredByTeacher(), true, pending.buzzWinner());
      submissionRepository.updateChallengedAnswer(challenged, true);

      assertEquals(0, classroomRepository.globalGroupSuccessfulBuzzCount(fixture.groupTwo),
          "Challenged group did not regain its current-day eligibility");
      var finalized = service.finalizeRound(fixture.questionId, 1, 1);
      assertEquals(pending.id(), finalized.winnerSubmissionId(),
          "Challenged submission was not finalized");
      assertEquals(fixture.groupTwo, finalized.winnerGroupNumber(),
          "Challenge target was not preserved for answer attribution");
      assertEquals(1, classroomRepository.globalGroupSuccessfulBuzzCount(fixture.groupOne),
          "Challenger's original group did not consume the successful buzz slot");
      assertEquals(0, classroomRepository.globalGroupSuccessfulBuzzCount(fixture.groupTwo),
          "Finalization consumed the challenged group's restored slot");
    }
  }

  private Fixture createFixture(Integer forcedGroupOne) throws SQLException {
    UUID teacherId = UUID.randomUUID();
    UUID studentOneId = UUID.randomUUID();
    UUID studentTwoId = UUID.randomUUID();
    UUID classroomId = UUID.randomUUID();
    UUID questionId = UUID.randomUUID();
    String suffix = questionId.toString().replace("-", "").substring(0, 10);
    int groupOne = forcedGroupOne == null
        ? 100_000 + Math.floorMod(questionId.hashCode(), 800_000)
        : forcedGroupOne;
    int groupTwo = groupOne + 1;
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      try {
        execute(connection, "INSERT INTO users (id, external_id, display_name, role) VALUES (?, ?, ?, 'TEACHER')",
            teacherId, "teacher-" + suffix, "Ranking Teacher");
        execute(connection, "INSERT INTO users (id, external_id, display_name, role) VALUES (?, ?, ?, 'STUDENT')",
            studentOneId, "student-one-" + suffix, "Student One / " + groupOne);
        execute(connection, "INSERT INTO users (id, external_id, display_name, role) VALUES (?, ?, ?, 'STUDENT')",
            studentTwoId, "student-two-" + suffix, "Student Two / " + groupTwo);
        execute(connection, "INSERT INTO classrooms "
                + "(id, teacher_id, classroom_code, name, status, participation_settings) "
                + "VALUES (?, ?, ?, 'Ranking Test', 'OPEN', '{}'::jsonb)",
            classroomId, teacherId, ("R" + suffix).toUpperCase());
        execute(connection, "INSERT INTO classroom_members "
                + "(id, classroom_id, user_id, role, presence_status, successful_buzz_count) "
                + "VALUES (?, ?, ?, 'STUDENT', 'ONLINE', 0)",
            UUID.randomUUID(), classroomId, studentOneId);
        execute(connection, "INSERT INTO classroom_members "
                + "(id, classroom_id, user_id, role, presence_status, successful_buzz_count) "
                + "VALUES (?, ?, ?, 'STUDENT', 'ONLINE', 0)",
            UUID.randomUUID(), classroomId, studentTwoId);
        execute(connection, "INSERT INTO questions "
                + "(id, classroom_id, ordinal, type, state, prompt, answer_key, publication_settings, score, answer_time_limit_seconds, created_by) "
                + "VALUES (?, ?, 1, 'BUZZ', 'BUZZ_OPEN', 'Ranking question', '{}'::jsonb, '{}'::jsonb, 0, 30, ?)",
            questionId, classroomId, teacherId);
        execute(connection,
            "INSERT INTO question_buzz_rounds (question_id, current_round) VALUES (?, 1)", questionId);
        connection.commit();
      } catch (SQLException | RuntimeException exception) {
        connection.rollback();
        throw exception;
      }
    }
    return new Fixture(teacherId, studentOneId, studentTwoId, classroomId, questionId, groupOne, groupTwo);
  }

  private int winnerSubmissionCount(UUID questionId) throws SQLException {
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM submissions WHERE question_id = ? AND round_number = 1 AND buzz_winner = TRUE")) {
      statement.setObject(1, questionId);
      ResultSet resultSet = statement.executeQuery();
      resultSet.next();
      return resultSet.getInt(1);
    }
  }

  private String attemptOutcome(UUID questionId, UUID userId) throws SQLException {
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement("""
             SELECT outcome_status FROM buzz_records
             WHERE question_id = ? AND user_id = ? ORDER BY receipt_order LIMIT 1
             """)) {
      statement.setObject(1, questionId);
      statement.setObject(2, userId);
      ResultSet resultSet = statement.executeQuery();
      if (!resultSet.next()) throw new AssertionError("Buzz attempt was not found");
      return resultSet.getString(1);
    }
  }

  private int submissionCount(UUID questionId, UUID userId) throws SQLException {
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(
             "SELECT COUNT(*) FROM submissions WHERE question_id = ? AND user_id = ? AND round_number = 1")) {
      statement.setObject(1, questionId);
      statement.setObject(2, userId);
      ResultSet resultSet = statement.executeQuery();
      resultSet.next();
      return resultSet.getInt(1);
    }
  }

  private String submissionReviewStatus(UUID questionId, UUID userId) throws SQLException {
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement("""
             SELECT review_status FROM submissions
             WHERE question_id = ? AND user_id = ? AND round_number = 1
             """)) {
      statement.setObject(1, questionId);
      statement.setObject(2, userId);
      ResultSet resultSet = statement.executeQuery();
      if (!resultSet.next()) throw new AssertionError("Submission was not found");
      return resultSet.getString(1);
    }
  }

  private void execute(String sql, Object... parameters) throws SQLException {
    try (Connection connection = database.openConnection()) {
      execute(connection, sql, parameters);
    }
  }

  private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      statement.executeUpdate();
    }
  }

  private static void assertTrue(boolean value, String message) {
    if (!value) throw new AssertionError(message);
  }

  private static void assertFalse(boolean value, String message) {
    assertTrue(!value, message);
  }

  private static void assertEquals(Object expected, Object actual, String message) {
    if (expected == null ? actual != null : !expected.equals(actual)) {
      throw new AssertionError(message + " (expected=" + expected + ", actual=" + actual + ")");
    }
  }

  private final class Fixture implements AutoCloseable {
    private final UUID teacherId;
    private final UUID studentOneId;
    private final UUID studentTwoId;
    private final UUID classroomId;
    private final UUID questionId;
    private final int groupOne;
    private final int groupTwo;

    private Fixture(
        UUID teacherId,
        UUID studentOneId,
        UUID studentTwoId,
        UUID classroomId,
        UUID questionId,
        int groupOne,
        int groupTwo
    ) {
      this.teacherId = teacherId;
      this.studentOneId = studentOneId;
      this.studentTwoId = studentTwoId;
      this.classroomId = classroomId;
      this.questionId = questionId;
      this.groupOne = groupOne;
      this.groupTwo = groupTwo;
    }

    private void saveAttempt(UUID userId, long receiptOrder, Instant legacyDeadline) {
      buzzRepository.save(new BuzzRecordEntity(
          UUID.randomUUID(), classroomId, questionId, userId, UUID.randomUUID().toString(),
          receiptOrder, Instant.now(), "RECORDED", false, legacyDeadline), 1);
    }

    private SubmissionEntity submission(UUID userId) {
      return new SubmissionEntity(
          UUID.randomUUID(), classroomId, questionId, userId, 1, "ranking answer",
          "RECEIVED", null, null, "PENDING_REVIEW", Instant.now(), null,
          userId.equals(studentOneId) ? groupOne : groupTwo, false, false, false);
    }

    @Override
    public void close() throws Exception {
      execute("DELETE FROM classrooms WHERE id = ?", classroomId);
      execute("DELETE FROM users WHERE id IN (?, ?, ?)", teacherId, studentOneId, studentTwoId);
      execute("DELETE FROM global_group_buzz_counts WHERE group_number IN (?, ?)", groupOne, groupTwo);
    }
  }
}
