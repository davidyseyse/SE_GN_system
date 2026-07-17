package com.classroom.api.data;

import com.classroom.api.config.ServerConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Database {
  private final String jdbcUrl;
  private static final int MAX_ATTEMPTS = 12;
  private static final long RETRY_DELAY_MS = 500L;

  public Database(ServerConfig config) {
    this.jdbcUrl = PostgresJdbcUrl.from(config.databaseUrl());
  }

  public Connection openConnection() throws SQLException {
    SQLException lastException = null;
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
      try {
        return DriverManager.getConnection(jdbcUrl);
      } catch (SQLException exception) {
        lastException = exception;
        if (attempt == MAX_ATTEMPTS) {
          break;
        }
        try {
          Thread.sleep(RETRY_DELAY_MS);
        } catch (InterruptedException interruptedException) {
          Thread.currentThread().interrupt();
          throw new SQLException("Interrupted while retrying database connection", interruptedException);
        }
      }
    }

    throw lastException;
  }
}
