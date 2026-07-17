package com.classroom.api.data.repository;

import com.classroom.api.data.Database;
import com.classroom.api.data.DatabaseException;
import com.classroom.api.data.JsonValue;
import com.classroom.api.data.SqlTime;
import com.classroom.api.data.model.AuditLogEntity;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public final class AuditLogRepository {
  private final Database database;

  public AuditLogRepository(Database database) {
    this.database = database;
  }

  public void save(AuditLogEntity auditLog) {
    String sql = """
        INSERT INTO audit_logs (id, classroom_id, actor_user_id, action_type, target_type, target_id, metadata, created_at)
        VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
        """;
    try (Connection connection = database.openConnection();
         PreparedStatement statement = connection.prepareStatement(sql)) {
      statement.setObject(1, auditLog.id());
      statement.setObject(2, auditLog.classroomId());
      statement.setObject(3, auditLog.actorUserId());
      statement.setString(4, auditLog.actionType());
      statement.setString(5, auditLog.targetType());
      statement.setObject(6, auditLog.targetId());
      statement.setString(7, JsonValue.normalize(auditLog.metadata()));
      statement.setTimestamp(8, SqlTime.from(auditLog.createdAt()));
      statement.executeUpdate();
    } catch (SQLException exception) {
      throw new DatabaseException("Unable to save audit log", exception);
    }
  }
}
