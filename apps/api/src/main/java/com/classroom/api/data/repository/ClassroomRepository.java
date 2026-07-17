package com.classroom.api.data.repository;

import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import com.classroom.api.data.JsonValue;
import com.classroom.api.data.SqlTime;
import com.classroom.api.data.model.ClassroomEntity;
import com.classroom.api.data.model.ClassroomMemberEntity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class ClassroomRepository {
  private final Database database;

  public ClassroomRepository(Database database) {
    this.database = database;
  }

  public void save(ClassroomEntity classroom) {
    String sql = """
        INSERT INTO classrooms (id, teacher_id, classroom_code, name, status, participation_settings, leaderboard_visibility, active_question_id, created_at, updated_at, opened_at, closed_at)
        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?)
        """;

    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, classroom.id());
      statement.setObject(2, classroom.teacherId());
      statement.setString(3, classroom.classroomCode());
      statement.setString(4, classroom.name());
      statement.setString(5, classroom.status());
      statement.setString(6, JsonValue.normalize(classroom.participationSettings()));
      statement.setString(7, classroom.leaderboardVisibility());
      statement.setObject(8, classroom.activeQuestionId());
      statement.setTimestamp(9, SqlTime.from(classroom.createdAt()));
      statement.setTimestamp(10, SqlTime.from(classroom.updatedAt()));
      statement.setTimestamp(11, SqlTime.from(classroom.openedAt()));
      statement.setTimestamp(12, SqlTime.from(classroom.closedAt()));
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to save classroom", exception);
    }
  }

  public ClassroomEntity findByCode(String classroomCode) {
    String sql = """
        SELECT id, teacher_id, classroom_code, name, status, participation_settings::text, leaderboard_visibility, active_question_id, created_at, updated_at, opened_at, closed_at
        FROM classrooms
        WHERE classroom_code = ?
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, classroomCode);
      ResultSet resultSet = statement.executeQuery();
      if (!resultSet.next()) {
        return null;
      }
      return mapClassroom(resultSet);
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to find classroom by code", exception);
    }
  }

  public ClassroomEntity findById(UUID classroomId) {
    String sql = """
        SELECT id, teacher_id, classroom_code, name, status, participation_settings::text, leaderboard_visibility, active_question_id, created_at, updated_at, opened_at, closed_at
        FROM classrooms
        WHERE id = ?
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, classroomId);
      ResultSet resultSet = statement.executeQuery();
      if (!resultSet.next()) {
        return null;
      }
      return mapClassroom(resultSet);
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to find classroom by id", exception);
    }
  }

  public void upsertMemberPresence(ClassroomMemberEntity member) {
    String sql = """
        INSERT INTO classroom_members (id, classroom_id, user_id, role, presence_status, joined_at, last_seen_at)
        VALUES (?, ?, ?, ?, ?, ?, ?)
        ON CONFLICT (classroom_id, user_id)
        DO UPDATE SET role = EXCLUDED.role, presence_status = EXCLUDED.presence_status, last_seen_at = EXCLUDED.last_seen_at
        """;

    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, member.id());
      statement.setObject(2, member.classroomId());
      statement.setObject(3, member.userId());
      statement.setString(4, member.role());
      statement.setString(5, member.presenceStatus());
      statement.setTimestamp(6, SqlTime.from(member.joinedAt()));
      statement.setTimestamp(7, SqlTime.from(member.lastSeenAt()));
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to upsert classroom member presence", exception);
    }
  }

  public List<ClassroomMemberEntity> listMembers(UUID classroomId) {
    String sql = """
        SELECT id, classroom_id, user_id, role, presence_status, joined_at, last_seen_at
        FROM classroom_members
        WHERE classroom_id = ?
        ORDER BY joined_at ASC
        """;
    List<ClassroomMemberEntity> results = new ArrayList<>();
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, classroomId);
      ResultSet resultSet = statement.executeQuery();
      while (resultSet.next()) {
        results.add(new ClassroomMemberEntity(
            resultSet.getObject("id", UUID.class),
            resultSet.getObject("classroom_id", UUID.class),
            resultSet.getObject("user_id", UUID.class),
            resultSet.getString("role"),
            resultSet.getString("presence_status"),
            resultSet.getTimestamp("joined_at").toInstant(),
            resultSet.getTimestamp("last_seen_at").toInstant()
        ));
      }
      return results;
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to list classroom members", exception);
    }
  }

  private static ClassroomEntity mapClassroom(ResultSet resultSet) throws SQLException {
    return new ClassroomEntity(
        resultSet.getObject("id", UUID.class),
        resultSet.getObject("teacher_id", UUID.class),
        resultSet.getString("classroom_code"),
        resultSet.getString("name"),
        resultSet.getString("status"),
        resultSet.getString("participation_settings"),
        resultSet.getString("leaderboard_visibility"),
        resultSet.getObject("active_question_id", UUID.class),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant(),
        resultSet.getTimestamp("opened_at") == null ? null : resultSet.getTimestamp("opened_at").toInstant(),
        resultSet.getTimestamp("closed_at") == null ? null : resultSet.getTimestamp("closed_at").toInstant()
    );
  }
}
