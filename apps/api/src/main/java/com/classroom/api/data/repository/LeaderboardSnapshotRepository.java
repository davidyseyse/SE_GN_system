package com.classroom.api.data.repository;

import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import com.classroom.api.data.model.LeaderboardSnapshotEntity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class LeaderboardSnapshotRepository {
  private final Database database;

  public LeaderboardSnapshotRepository(Database database) {
    this.database = database;
  }

  public void save(LeaderboardSnapshotEntity snapshot) {
    String sql = """
        INSERT INTO leaderboard_snapshots (id, classroom_id, snapshot_at, ranking_data, visibility)
        VALUES (?, ?, ?, CAST(? AS jsonb), ?)
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, snapshot.id());
      statement.setObject(2, snapshot.classroomId());
      statement.setObject(3, snapshot.snapshotAt());
      statement.setString(4, snapshot.rankingData());
      statement.setString(5, snapshot.visibility());
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to save leaderboard snapshot", exception);
    }
  }
}
