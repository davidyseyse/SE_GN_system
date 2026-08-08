package com.classroom.api.data.repository;

import com.classroom.api.contract.QuestionState;
import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import com.classroom.api.data.model.CurrentBuzzStatus;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class CurrentBuzzStatusRepository {
  private final Database database;

  public CurrentBuzzStatusRepository(Database database) {
    this.database = database;
  }

  public Map<UUID, CurrentBuzzStatus> listByClassroom(UUID classroomId) {
    String sql = """
        SELECT q.id AS question_id,
               q.state,
               COALESCE(qbr.current_round, 0) AS round_number,
               winner.user_id AS winner_user_id,
               winner_user.external_id AS winner_external_id,
               winner_user.display_name AS winner_display_name,
               winner.answer_deadline_at,
               submission.submission_status,
               submission.review_status
        FROM questions q
        LEFT JOIN question_buzz_rounds qbr ON qbr.question_id = q.id
        LEFT JOIN LATERAL (
          SELECT br.user_id, br.answer_deadline_at
          FROM buzz_records br
          WHERE br.question_id = q.id
            AND br.round_number = COALESCE(qbr.current_round, 0)
            AND br.successful = TRUE
            AND br.active = TRUE
          ORDER BY br.receipt_order ASC, br.received_at ASC
          LIMIT 1
        ) winner ON TRUE
        LEFT JOIN users winner_user ON winner_user.id = winner.user_id
        LEFT JOIN LATERAL (
          SELECT s.submission_status, s.review_status
          FROM submissions s
          WHERE s.question_id = q.id
            AND s.round_number = COALESCE(qbr.current_round, 0)
            AND (winner.user_id IS NULL OR s.user_id = winner.user_id)
          ORDER BY s.submitted_at DESC
          LIMIT 1
        ) submission ON TRUE
        WHERE q.classroom_id = ?
        ORDER BY q.ordinal
        """;
    Map<UUID, CurrentBuzzStatus> results = new LinkedHashMap<>();
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, classroomId);
      ResultSet resultSet = statement.executeQuery();
      while (resultSet.next()) {
        UUID questionId = resultSet.getObject("question_id", UUID.class);
        String state = resultSet.getString("state");
        UUID winnerUserId = resultSet.getObject("winner_user_id", UUID.class);
        String submissionStatus = resultSet.getString("submission_status");
        String buzzStatus = deriveStatus(state, winnerUserId, submissionStatus);
        results.put(questionId, new CurrentBuzzStatus(
            questionId,
            resultSet.getInt("round_number"),
            buzzStatus,
            winnerUserId,
            resultSet.getString("winner_external_id"),
            resultSet.getString("winner_display_name"),
            resultSet.getTimestamp("answer_deadline_at") == null
                ? null : resultSet.getTimestamp("answer_deadline_at").toInstant(),
            submissionStatus,
            resultSet.getString("review_status")
        ));
      }
      return results;
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to load current buzz statuses", exception);
    }
  }

  private static String deriveStatus(String state, UUID winnerUserId, String submissionStatus) {
    QuestionState questionState = QuestionState.valueOf(state);
    if (questionState == QuestionState.LOCKED) {
      return "STOPPED";
    }
    if (questionState != QuestionState.BUZZ_OPEN && questionState != QuestionState.ANSWER_OPEN) {
      return "NOT_OPEN";
    }
    if (submissionStatus != null) {
      return "SUBMITTED_PENDING_REVIEW";
    }
    if (winnerUserId != null) {
      return "WINNER_ANSWERING";
    }
    return "WAITING_FOR_BUZZ";
  }
}
