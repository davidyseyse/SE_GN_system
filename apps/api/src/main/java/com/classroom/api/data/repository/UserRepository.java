package com.classroom.api.data.repository;

import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import com.classroom.api.data.model.UserEntity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public final class UserRepository {
  private final Database database;

  public UserRepository(Database database) {
    this.database = database;
  }

  public void save(UserEntity user) {
    String sql = """
        INSERT INTO users (id, external_id, display_name, role, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, user.id());
      statement.setString(2, user.externalId());
      statement.setString(3, user.displayName());
      statement.setString(4, user.role());
      statement.setObject(5, user.createdAt());
      statement.setObject(6, user.updatedAt());
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to save user", exception);
    }
  }

  public UserEntity findByExternalId(String externalId) {
    String sql = """
        SELECT id, external_id, display_name, role, created_at, updated_at
        FROM users
        WHERE external_id = ?
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setString(1, externalId);
      ResultSet resultSet = statement.executeQuery();
      if (!resultSet.next()) {
        return null;
      }
      return new UserEntity(
          resultSet.getObject("id", UUID.class),
          resultSet.getString("external_id"),
          resultSet.getString("display_name"),
          resultSet.getString("role"),
          resultSet.getTimestamp("created_at").toInstant(),
          resultSet.getTimestamp("updated_at").toInstant()
      );
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to find user", exception);
    }
  }

  public UserEntity findById(UUID userId) {
    String sql = """
        SELECT id, external_id, display_name, role, created_at, updated_at
        FROM users
        WHERE id = ?
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, userId);
      ResultSet resultSet = statement.executeQuery();
      if (!resultSet.next()) {
        return null;
      }
      return new UserEntity(
          resultSet.getObject("id", UUID.class),
          resultSet.getString("external_id"),
          resultSet.getString("display_name"),
          resultSet.getString("role"),
          resultSet.getTimestamp("created_at").toInstant(),
          resultSet.getTimestamp("updated_at").toInstant()
      );
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to find user", exception);
    }
  }
}
