package com.classroom.api.data.repository;

import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import com.classroom.api.data.SqlTime;
import com.classroom.api.data.model.SheetConfigEntity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class SheetConfigRepository {
  private final Database database;

  public SheetConfigRepository(Database database) {
    this.database = database;
  }

  public void save(SheetConfigEntity config) {
    String sql = """
        INSERT INTO sheet_configs (id, name, sheet_id, created_at, updated_at)
        VALUES (?, ?, ?, ?, ?)
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, config.id());
      statement.setString(2, config.name());
      statement.setString(3, config.sheetId());
      statement.setTimestamp(4, SqlTime.from(config.createdAt()));
      statement.setTimestamp(5, SqlTime.from(config.updatedAt()));
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to save sheet config", exception);
    }
  }

  public List<SheetConfigEntity> list() {
    String sql = """
        SELECT id, name, sheet_id, created_at, updated_at
        FROM sheet_configs
        ORDER BY created_at ASC
        """;
    List<SheetConfigEntity> results = new ArrayList<>();
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      ResultSet resultSet = statement.executeQuery();
      while (resultSet.next()) {
        results.add(map(resultSet));
      }
      return results;
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to list sheet configs", exception);
    }
  }

  public SheetConfigEntity findById(UUID id) {
    String sql = """
        SELECT id, name, sheet_id, created_at, updated_at
        FROM sheet_configs
        WHERE id = ?
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      ResultSet resultSet = statement.executeQuery();
      if (!resultSet.next()) {
        return null;
      }
      return map(resultSet);
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to find sheet config", exception);
    }
  }

  public void delete(UUID id) {
    String sql = "DELETE FROM sheet_configs WHERE id = ?";
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, id);
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to delete sheet config", exception);
    }
  }

  private static SheetConfigEntity map(ResultSet resultSet) throws SQLException {
    Instant createdAt = resultSet.getTimestamp("created_at").toInstant();
    Instant updatedAt = resultSet.getTimestamp("updated_at").toInstant();
    return new SheetConfigEntity(
        resultSet.getObject("id", UUID.class),
        resultSet.getString("name"),
        resultSet.getString("sheet_id"),
        createdAt,
        updatedAt
    );
  }
}
