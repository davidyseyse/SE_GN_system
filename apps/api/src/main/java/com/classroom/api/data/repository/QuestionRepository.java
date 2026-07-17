package com.classroom.api.data.repository;

import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import com.classroom.api.data.JsonValue;
import com.classroom.api.data.SqlTime;
import com.classroom.api.data.model.QuestionEntity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class QuestionRepository {
  private final Database database;

  public QuestionRepository(Database database) {
    this.database = database;
  }

  public void save(QuestionEntity question) {
    String sql = """
        INSERT INTO questions (id, classroom_id, ordinal, type, state, prompt, answer_key, publication_settings, score, answer_time_limit_seconds, created_by, created_at, updated_at, archived_at)
        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?, ?)
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, question.id());
      statement.setObject(2, question.classroomId());
      statement.setInt(3, question.ordinal());
      statement.setString(4, question.type());
      statement.setString(5, question.state());
      statement.setString(6, question.prompt());
      statement.setString(7, JsonValue.normalize(question.answerKey()));
      statement.setString(8, JsonValue.normalize(question.publicationSettings()));
      statement.setInt(9, question.score());
      if (question.answerTimeLimitSeconds() == null) {
        statement.setNull(10, java.sql.Types.INTEGER);
      } else {
        statement.setInt(10, question.answerTimeLimitSeconds());
      }
      statement.setObject(11, question.createdBy());
      statement.setTimestamp(12, SqlTime.from(question.createdAt()));
      statement.setTimestamp(13, SqlTime.from(question.updatedAt()));
      statement.setTimestamp(14, SqlTime.from(question.archivedAt()));
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to save question", exception);
    }
  }

  public List<QuestionEntity> listByClassroom(UUID classroomId) {
    String sql = """
        SELECT id, classroom_id, ordinal, type, state, prompt, answer_key::text, publication_settings::text, score, answer_time_limit_seconds, created_by, created_at, updated_at, archived_at
        FROM questions
        WHERE classroom_id = ?
        ORDER BY ordinal ASC
        """;
    List<QuestionEntity> results = new ArrayList<>();
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, classroomId);
      ResultSet resultSet = statement.executeQuery();
      while (resultSet.next()) {
        results.add(new QuestionEntity(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("classroom_id", UUID.class),
            resultSet.getInt("ordinal"),
            resultSet.getString("type"),
            resultSet.getString("state"),
            resultSet.getString("prompt"),
            resultSet.getString("answer_key"),
            resultSet.getString("publication_settings"),
            resultSet.getInt("score"),
            (Integer) resultSet.getObject("answer_time_limit_seconds"),
            resultSet.getObject("created_by", UUID.class),
            resultSet.getTimestamp("created_at").toInstant(),
            resultSet.getTimestamp("updated_at").toInstant(),
            resultSet.getTimestamp("archived_at") == null ? null : resultSet.getTimestamp("archived_at").toInstant()
        ));
      }
      return results;
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to list questions", exception);
    }
  }

  public QuestionEntity findById(UUID classroomId, UUID questionId) {
    String sql = """
        SELECT id, classroom_id, ordinal, type, state, prompt, answer_key::text, publication_settings::text, score, answer_time_limit_seconds, created_by, created_at, updated_at, archived_at
        FROM questions
        WHERE classroom_id = ? AND id = ?
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, classroomId);
      statement.setObject(2, questionId);
      ResultSet resultSet = statement.executeQuery();
      if (!resultSet.next()) {
        return null;
      }
      return mapQuestion(resultSet);
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to find question", exception);
    }
  }

  public void update(QuestionEntity question) {
    String sql = """
        UPDATE questions
        SET ordinal = ?, type = ?, state = ?, prompt = ?, answer_key = CAST(? AS jsonb), publication_settings = CAST(? AS jsonb), score = ?, answer_time_limit_seconds = ?, updated_at = ?, archived_at = ?
        WHERE id = ? AND classroom_id = ?
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, question.ordinal());
      statement.setString(2, question.type());
      statement.setString(3, question.state());
      statement.setString(4, question.prompt());
      statement.setString(5, JsonValue.normalize(question.answerKey()));
      statement.setString(6, JsonValue.normalize(question.publicationSettings()));
      statement.setInt(7, question.score());
      if (question.answerTimeLimitSeconds() == null) {
        statement.setNull(8, java.sql.Types.INTEGER);
      } else {
        statement.setInt(8, question.answerTimeLimitSeconds());
      }
      statement.setTimestamp(9, SqlTime.from(question.updatedAt()));
      statement.setTimestamp(10, SqlTime.from(question.archivedAt()));
      statement.setObject(11, question.id());
      statement.setObject(12, question.classroomId());
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to update question", exception);
    }
  }

  public void reorder(UUID classroomId, UUID questionId, int newOrdinal) {
    List<QuestionEntity> questions = listByClassroom(classroomId);
    QuestionEntity target = null;
    for (QuestionEntity question : questions) {
      if (question.id().equals(questionId)) {
        target = question;
        break;
      }
    }

    if (target == null) {
      throw new IllegalArgumentException("Question not found");
    }

    if (newOrdinal < 1 || newOrdinal > questions.size()) {
      throw new IllegalArgumentException("newOrdinal out of range");
    }

    questions.remove(target);
    questions.add(newOrdinal - 1, target);

    try (Connection connection = database.openConnection()) {
      connection.setAutoCommit(false);
      try (PreparedStatement statement = connection.prepareStatement("""
          UPDATE questions
          SET ordinal = ?, updated_at = NOW()
          WHERE id = ? AND classroom_id = ?
          """)) {
        for (int index = 0; index < questions.size(); index++) {
          QuestionEntity question = questions.get(index);
          statement.setInt(1, index + 1);
          statement.setObject(2, question.id());
          statement.setObject(3, classroomId);
          statement.addBatch();
        }
        statement.executeBatch();
        connection.commit();
      } catch (SQLException exception) {
        connection.rollback();
        throw exception;
      } finally {
        connection.setAutoCommit(true);
      }
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to reorder questions", exception);
    }
  }

  private static QuestionEntity mapQuestion(ResultSet resultSet) throws SQLException {
    return new QuestionEntity(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("classroom_id", UUID.class),
        resultSet.getInt("ordinal"),
        resultSet.getString("type"),
        resultSet.getString("state"),
        resultSet.getString("prompt"),
        resultSet.getString("answer_key"),
        resultSet.getString("publication_settings"),
        resultSet.getInt("score"),
        (Integer) resultSet.getObject("answer_time_limit_seconds"),
        resultSet.getObject("created_by", UUID.class),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant(),
        resultSet.getTimestamp("archived_at") == null ? null : resultSet.getTimestamp("archived_at").toInstant()
    );
  }
}
