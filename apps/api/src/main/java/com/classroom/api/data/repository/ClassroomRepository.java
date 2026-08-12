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
        INSERT INTO classrooms (id, teacher_id, classroom_code, name, status, participation_settings, active_question_id, created_at, updated_at, opened_at, closed_at)
        VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?)
        """;

    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, classroom.id());
      statement.setObject(2, classroom.teacherId());
      statement.setString(3, classroom.classroomCode());
      statement.setString(4, classroom.name());
      statement.setString(5, classroom.status());
      statement.setString(6, JsonValue.normalize(classroom.participationSettings()));
      statement.setObject(7, classroom.activeQuestionId());
      statement.setTimestamp(8, SqlTime.from(classroom.createdAt()));
      statement.setTimestamp(9, SqlTime.from(classroom.updatedAt()));
      statement.setTimestamp(10, SqlTime.from(classroom.openedAt()));
      statement.setTimestamp(11, SqlTime.from(classroom.closedAt()));
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to save classroom", exception);
    }
  }

  public ClassroomEntity findByCode(String classroomCode) {
    String sql = """
        SELECT id, teacher_id, classroom_code, name, status, participation_settings::text, active_question_id, created_at, updated_at, opened_at, closed_at
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
        SELECT id, teacher_id, classroom_code, name, status, participation_settings::text, active_question_id, created_at, updated_at, opened_at, closed_at
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

  public List<ClassroomEntity> listByTeacherId(UUID teacherId) {
    String sql = """
        SELECT id, teacher_id, classroom_code, name, status, participation_settings::text, active_question_id, created_at, updated_at, opened_at, closed_at
        FROM classrooms
        WHERE teacher_id = ?
        ORDER BY created_at DESC
        """;
    List<ClassroomEntity> results = new ArrayList<>();
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, teacherId);
      ResultSet resultSet = statement.executeQuery();
      while (resultSet.next()) {
        results.add(mapClassroom(resultSet));
      }
      return results;
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to list teacher classrooms", exception);
    }
  }

  public List<ClassroomEntity> listAll() {
    String sql = """
        SELECT id, teacher_id, classroom_code, name, status, participation_settings::text, active_question_id, created_at, updated_at, opened_at, closed_at
        FROM classrooms
        ORDER BY created_at DESC
        """;
    List<ClassroomEntity> results = new ArrayList<>();
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      ResultSet resultSet = statement.executeQuery();
      while (resultSet.next()) {
        results.add(mapClassroom(resultSet));
      }
      return results;
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to list classrooms", exception);
    }
  }

  public boolean deleteOwnedClassroom(UUID classroomId, UUID teacherId) {
    String sql = "DELETE FROM classrooms WHERE id = ? AND teacher_id = ?";
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, classroomId);
      statement.setObject(2, teacherId);
      return statement.executeUpdate() == 1;
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to delete classroom", exception);
    }
  }

  public void upsertMemberPresence(ClassroomMemberEntity member) {
    String sql = """
        INSERT INTO classroom_members (id, classroom_id, user_id, role, presence_status, joined_at, last_seen_at, successful_buzz_count)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
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
      statement.setInt(8, member.successfulBuzzCount());
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to upsert classroom member presence", exception);
    }
  }

  public List<ClassroomMemberEntity> listMembers(UUID classroomId) {
    String sql = """
        SELECT id, classroom_id, user_id, role, presence_status, joined_at, last_seen_at, successful_buzz_count
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
            resultSet.getTimestamp("last_seen_at").toInstant(),
            resultSet.getInt("successful_buzz_count")
        ));
      }
      return results;
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to list classroom members", exception);
    }
  }

  public boolean hasMember(UUID classroomId, UUID userId) {
    String sql = """
        SELECT 1
        FROM classroom_members
        WHERE classroom_id = ? AND user_id = ?
        LIMIT 1
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, classroomId);
      statement.setObject(2, userId);
      return statement.executeQuery().next();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to check classroom membership", exception);
    }
  }

  public int successfulBuzzCount(UUID classroomId, UUID userId) {
    String sql = "SELECT successful_buzz_count FROM classroom_members WHERE classroom_id = ? AND user_id = ?";
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, classroomId);
      statement.setObject(2, userId);
      ResultSet resultSet = statement.executeQuery();
      return resultSet.next() ? resultSet.getInt(1) : 0;
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to load successful buzz count", exception);
    }
  }

  public Integer incrementSuccessfulBuzzCount(
      Connection connection,
      UUID classroomId,
      UUID userId,
      int successfulBuzzLimit
  ) throws SQLException {
    String sql = """
        UPDATE classroom_members
        SET successful_buzz_count = successful_buzz_count + 1,
            last_seen_at = NOW()
        WHERE classroom_id = ? AND user_id = ?
          AND (? <= 0 OR successful_buzz_count < ?)
        RETURNING successful_buzz_count
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, classroomId);
      statement.setObject(2, userId);
      statement.setInt(3, successfulBuzzLimit);
      statement.setInt(4, successfulBuzzLimit);
      ResultSet resultSet = statement.executeQuery();
      return resultSet.next() ? resultSet.getInt(1) : null;
    }
  }

  public int globalGroupSuccessfulBuzzCount(int groupNumber) {
    String sql = "SELECT successful_buzz_count FROM global_group_buzz_counts WHERE group_number = ?";
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, groupNumber);
      ResultSet resultSet = statement.executeQuery();
      return resultSet.next() ? resultSet.getInt(1) : 0;
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to load global group successful buzz count", exception);
    }
  }

  public Integer incrementGlobalGroupSuccessfulBuzzCount(
      Connection connection,
      int groupNumber,
      int successfulBuzzLimit
  ) throws SQLException {
    String sql = """
        INSERT INTO global_group_buzz_counts
          (group_number, successful_buzz_count, updated_at)
        VALUES (?, 1, NOW())
        ON CONFLICT (group_number) DO UPDATE
        SET successful_buzz_count = global_group_buzz_counts.successful_buzz_count + 1,
            updated_at = NOW()
        WHERE global_group_buzz_counts.successful_buzz_count < ?
        RETURNING successful_buzz_count
        """;
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setInt(1, groupNumber);
      statement.setInt(2, successfulBuzzLimit);
      ResultSet resultSet = statement.executeQuery();
      return resultSet.next() ? resultSet.getInt(1) : null;
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
        resultSet.getObject("active_question_id", UUID.class),
        resultSet.getTimestamp("created_at").toInstant(),
        resultSet.getTimestamp("updated_at").toInstant(),
        resultSet.getTimestamp("opened_at") == null ? null : resultSet.getTimestamp("opened_at").toInstant(),
        resultSet.getTimestamp("closed_at") == null ? null : resultSet.getTimestamp("closed_at").toInstant()
    );
  }
}
