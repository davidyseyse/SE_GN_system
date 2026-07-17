package com.classroom.api.data;

import com.classroom.api.config.ServerConfig;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public final class Database {
  private final String jdbcUrl;

  public Database(ServerConfig config) {
    this.jdbcUrl = PostgresJdbcUrl.from(config.databaseUrl());
  }

  public Connection openConnection() throws SQLException {
    return DriverManager.getConnection(jdbcUrl);
  }
}
