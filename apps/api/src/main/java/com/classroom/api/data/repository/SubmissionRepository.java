package com.classroom.api.data.repository;

import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import com.classroom.api.data.JsonValue;
import com.classroom.api.data.SqlTime;
import com.classroom.api.data.model.SubmissionEntity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SubmissionRepository {
  private final Database database;

  public SubmissionRepository(Database database) {
    this.database = database;
  }

  public void save(SubmissionEntity submission) {
    String sql = """
        INSERT INTO submissions (id, classroom_id, question_id, user_id, attempt_number, submitted_answer, submission_status, is_correct, awarded_score, review_status, submitted_at, reviewed_at)
        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?)
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, submission.id());
      statement.setObject(2, submission.classroomId());
      statement.setObject(3, submission.questionId());
      statement.setObject(4, submission.userId());
      statement.setInt(5, submission.attemptNumber());
      statement.setString(6, JsonValue.normalize(submission.submittedAnswer()));
      statement.setString(7, submission.submissionStatus());
      statement.setObject(8, submission.isCorrect());
      statement.setObject(9, submission.awardedScore());
      statement.setString(10, submission.reviewStatus());
      statement.setTimestamp(11, SqlTime.from(submission.submittedAt()));
      statement.setTimestamp(12, SqlTime.from(submission.reviewedAt()));
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to save submission", exception);
    }
  }

  public List<SubmissionEntity> listByQuestionId(UUID questionId) {
    String sql = """
        SELECT id, classroom_id, question_id, user_id, attempt_number, submitted_answer::text, submission_status, is_correct, awarded_score, review_status, submitted_at, reviewed_at
        FROM submissions
        WHERE question_id = ?
        ORDER BY submitted_at ASC
        """;
    List<SubmissionEntity> results = new ArrayList<>();
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, questionId);
      ResultSet resultSet = statement.executeQuery();
      while (resultSet.next()) {
        results.add(new SubmissionEntity(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("classroom_id", UUID.class),
            resultSet.getObject("question_id", UUID.class),
            resultSet.getObject("user_id", UUID.class),
            resultSet.getInt("attempt_number"),
            resultSet.getString("submitted_answer"),
            resultSet.getString("submission_status"),
            (Boolean) resultSet.getObject("is_correct"),
            (Integer) resultSet.getObject("awarded_score"),
            resultSet.getString("review_status"),
            resultSet.getTimestamp("submitted_at").toInstant(),
            resultSet.getTimestamp("reviewed_at") == null ? null : resultSet.getTimestamp("reviewed_at").toInstant()
        ));
      }
      return results;
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to list submissions", exception);
    }
  }

  public boolean hasSubmission(UUID questionId, UUID userId) {
    String sql = """
        SELECT 1
        FROM submissions
        WHERE question_id = ? AND user_id = ?
        LIMIT 1
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, questionId);
      statement.setObject(2, userId);
      ResultSet resultSet = statement.executeQuery();
      return resultSet.next();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to check existing submission", exception);
    }
  }

  public SubmissionEntity findById(UUID submissionId) {
    String sql = """
        SELECT id, classroom_id, question_id, user_id, attempt_number, submitted_answer::text, submission_status, is_correct, awarded_score, review_status, submitted_at, reviewed_at
        FROM submissions
        WHERE id = ?
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, submissionId);
      ResultSet resultSet = statement.executeQuery();
      if (!resultSet.next()) {
        return null;
      }
      return mapSubmission(resultSet);
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to find submission", exception);
    }
  }

  public void updateReview(SubmissionEntity submission) {
    String sql = """
        UPDATE submissions
        SET is_correct = ?, awarded_score = ?, review_status = ?, reviewed_at = ?
        WHERE id = ?
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, submission.isCorrect());
      statement.setObject(2, submission.awardedScore());
      statement.setString(3, submission.reviewStatus());
      statement.setTimestamp(4, SqlTime.from(submission.reviewedAt()));
      statement.setObject(5, submission.id());
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to update reviewed submission", exception);
    }
  }

  public void updateAnswer(SubmissionEntity submission) {
    String sql = """
        UPDATE submissions
        SET submitted_answer = CAST(? AS jsonb)
        WHERE id = ?
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, JsonValue.normalize(submission.submittedAnswer()));
      statement.setObject(2, submission.id());
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to update submission answer", exception);
    }
  }

  private static SubmissionEntity mapSubmission(ResultSet resultSet) throws SQLException {
    return new SubmissionEntity(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("classroom_id", UUID.class),
        resultSet.getObject("question_id", UUID.class),
        resultSet.getObject("user_id", UUID.class),
        resultSet.getInt("attempt_number"),
        resultSet.getString("submitted_answer"),
        resultSet.getString("submission_status"),
        (Boolean) resultSet.getObject("is_correct"),
        (Integer) resultSet.getObject("awarded_score"),
        resultSet.getString("review_status"),
        resultSet.getTimestamp("submitted_at").toInstant(),
        resultSet.getTimestamp("reviewed_at") == null ? null : resultSet.getTimestamp("reviewed_at").toInstant()
    );
  }
}
