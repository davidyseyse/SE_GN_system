package com.classroom.api.data;

public final class PostgresJdbcUrl {
  private PostgresJdbcUrl() {
  }

  public static String from(String databaseUrl) {
    if (databaseUrl.startsWith("jdbc:")) {
      return databaseUrl;
    }

    if (!databaseUrl.startsWith("postgres://")) {
      throw new IllegalArgumentException("Unsupported DATABASE_URL format: " + databaseUrl);
    }

    String withoutScheme = databaseUrl.substring("postgres://".length());
    int atIndex = withoutScheme.indexOf('@');
    int slashIndex = withoutScheme.indexOf('/', atIndex + 1);
    String credentials = withoutScheme.substring(0, atIndex);
    String hostPort = withoutScheme.substring(atIndex + 1, slashIndex);
    String databaseName = withoutScheme.substring(slashIndex + 1);

    String[] credentialParts = credentials.split(":", 2);
    if (credentialParts.length != 2) {
      throw new IllegalArgumentException("DATABASE_URL must contain username and password");
    }

    String querySuffix = "&sslmode=disable";
    return "jdbc:postgresql://" + hostPort + "/" + databaseName
        + "?user=" + credentialParts[0]
        + "&password=" + credentialParts[1]
        + querySuffix;
  }
}
