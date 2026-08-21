package com.classroom.api.question;

import com.classroom.api.contract.QuestionState;
import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import com.classroom.api.data.model.ClassroomEntity;
import com.classroom.api.data.model.QuestionEntity;
import com.classroom.api.data.repository.QuestionRepository;
import com.classroom.api.redis.RedisBuzzSequenceService;
import com.classroom.api.realtime.RealtimeEventBroker;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class QuestionLifecycleService {
  private final Database database;
  private final QuestionRepository questionRepository;
  private final RedisBuzzSequenceService redisBuzzSequenceService;
  private final RealtimeEventBroker eventBroker;
  private final QuestionStateMachine stateMachine = new QuestionStateMachine();

  public QuestionLifecycleService(
      Database database,
      QuestionRepository questionRepository,
      RedisBuzzSequenceService redisBuzzSequenceService,
      RealtimeEventBroker eventBroker
  ) {
    this.database = database;
    this.questionRepository = questionRepository;
    this.redisBuzzSequenceService = redisBuzzSequenceService;
    this.eventBroker = eventBroker;
  }

  public OperationResult transition(ClassroomEntity classroom, QuestionEntity question, QuestionState targetState) {
    QuestionState previousState;
    int roundNumber;
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      try {
        previousState = lockState(connection, classroom, question);
        stateMachine.assertTransitionAllowed(previousState, targetState);
        updateState(connection, classroom, question, targetState);
        roundNumber = targetState == QuestionState.BUZZ_OPEN
            ? startNextRound(connection, question)
            : currentRound(connection, question);
        connection.commit();
      } catch (Exception exception) {
        connection.rollback();
        throw exception;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to transition question state", exception);
    }

    publishStateChanged(classroom, question, targetState, roundNumber);
    return new OperationResult(question.id().toString(), previousState.name(), targetState.name(),
        "CHANGED", roundNumber, "");
  }

  public OperationResult reset(ClassroomEntity classroom, QuestionEntity question) {
    return reset(classroom, question, false);
  }

  public OperationResult resetAndClearRecords(ClassroomEntity classroom, QuestionEntity question) {
    return reset(classroom, question, true);
  }

  private OperationResult reset(
      ClassroomEntity classroom,
      QuestionEntity question,
      boolean clearRecords
  ) {
    int roundNumber = currentRound(question);
    QuestionState currentState = QuestionState.valueOf(question.state());
    if (currentState != QuestionState.BUZZ_OPEN
        && currentState != QuestionState.ANSWER_OPEN
        && currentState != QuestionState.LOCKED
        && !(clearRecords && currentState == QuestionState.READY)) {
      return new OperationResult(question.id().toString(), currentState.name(), currentState.name(),
          "SKIPPED", roundNumber, "Question is not active or locked");
    }

    int updated;
    int deletedRecords = 0;
    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      try {
        QuestionState lockedState = lockState(connection, classroom, question);
        if (lockedState != QuestionState.BUZZ_OPEN
            && lockedState != QuestionState.ANSWER_OPEN
            && lockedState != QuestionState.LOCKED
            && !(clearRecords && lockedState == QuestionState.READY)) {
          connection.rollback();
          return new OperationResult(question.id().toString(), currentState.name(), lockedState.name(),
              "UNCHANGED", roundNumber, "Question state changed concurrently");
        }

        updated = updateResetState(connection, classroom, question);
        if (clearRecords) {
          deletedRecords = deleteAnswerRecords(connection, classroom, question);
        }
        connection.commit();
      } catch (Exception exception) {
        connection.rollback();
        throw exception;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to reset question", exception);
    }

    if (updated == 0 && deletedRecords == 0) {
      return new OperationResult(question.id().toString(), currentState.name(), currentState.name(),
          "SKIPPED", roundNumber, "Question is already ready and has no answer records");
    }

    redisBuzzSequenceService.delete("buzz:sequence:" + question.id() + ":" + roundNumber);
    eventBroker.publish(classroom.classroomCode(), "BUZZ_RESET", Map.of(
        "packageId", question.id().toString(),
        "questionId", question.id().toString(),
        "roundId", Integer.toString(roundNumber)
    ));
    return new OperationResult(question.id().toString(), currentState.name(), QuestionState.READY.name(),
        "CHANGED", roundNumber, "");
  }

  private static int updateResetState(
      Connection connection,
      ClassroomEntity classroom,
      QuestionEntity question
  ) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        UPDATE questions
        SET state = 'READY', updated_at = NOW()
        WHERE id = ? AND classroom_id = ? AND state IN ('BUZZ_OPEN', 'ANSWER_OPEN', 'LOCKED')
        """)) {
      statement.setObject(1, question.id());
      statement.setObject(2, classroom.id());
      return statement.executeUpdate();
    }
  }

  private static int deleteAnswerRecords(
      Connection connection,
      ClassroomEntity classroom,
      QuestionEntity question
  ) throws SQLException {
    int deleted = 0;
    for (String table : new String[]{"submissions", "buzz_records"}) {
      try (PreparedStatement statement = connection.prepareStatement(
          "DELETE FROM " + table + " WHERE classroom_id = ? AND question_id = ?")) {
        statement.setObject(1, classroom.id());
        statement.setObject(2, question.id());
        deleted += statement.executeUpdate();
      }
    }
    return deleted;
  }

  public int currentRound(QuestionEntity question) {
    try (Connection connection = database.openConnection()) {
      return currentRound(connection, question);
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to load current round", exception);
    }
  }

  private QuestionState lockState(Connection connection, ClassroomEntity classroom, QuestionEntity question)
      throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        SELECT state FROM questions WHERE id = ? AND classroom_id = ? FOR UPDATE
        """)) {
      statement.setObject(1, question.id());
      statement.setObject(2, classroom.id());
      ResultSet resultSet = statement.executeQuery();
      if (!resultSet.next()) {
        throw new IllegalArgumentException("Question not found");
      }
      return QuestionState.valueOf(resultSet.getString(1));
    }
  }

  private static void updateState(
      Connection connection,
      ClassroomEntity classroom,
      QuestionEntity question,
      QuestionState targetState
  ) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        UPDATE questions SET state = ?, updated_at = NOW() WHERE id = ? AND classroom_id = ?
        """)) {
      statement.setString(1, targetState.name());
      statement.setObject(2, question.id());
      statement.setObject(3, classroom.id());
      statement.executeUpdate();
    }
  }

  private static int startNextRound(Connection connection, QuestionEntity question) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement("""
        INSERT INTO question_buzz_rounds (question_id, current_round)
        VALUES (?, 1)
        ON CONFLICT (question_id) DO UPDATE
        SET current_round = question_buzz_rounds.current_round + 1, updated_at = NOW()
        RETURNING current_round
        """)) {
      statement.setObject(1, question.id());
      ResultSet resultSet = statement.executeQuery();
      resultSet.next();
      return resultSet.getInt(1);
    }
  }

  private static int currentRound(Connection connection, QuestionEntity question) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(
        "SELECT current_round FROM question_buzz_rounds WHERE question_id = ?")) {
      statement.setObject(1, question.id());
      ResultSet resultSet = statement.executeQuery();
      return resultSet.next() ? resultSet.getInt(1) : 0;
    }
  }

  private void publishStateChanged(
      ClassroomEntity classroom,
      QuestionEntity question,
      QuestionState state,
      int roundNumber
  ) {
    eventBroker.publish(classroom.classroomCode(), "QUESTION_STATE_CHANGED", Map.of(
        "packageId", question.id().toString(),
        "questionId", question.id().toString(),
        "roundId", Integer.toString(roundNumber),
        "state", state.name(),
        "prompt", question.prompt()
    ));
  }

  public record OperationResult(
      String questionId,
      String previousState,
      String resultingState,
      String result,
      int roundId,
      String reason
  ) {
    public Map<String, Object> toMap() {
      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("packageId", questionId);
      payload.put("questionId", questionId);
      payload.put("previousState", previousState);
      payload.put("resultingState", resultingState);
      payload.put("result", result);
      payload.put("roundId", Integer.toString(roundId));
      payload.put("reason", reason);
      return payload;
    }
  }
}
