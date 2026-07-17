package com.classroom.api.data;

import java.sql.Timestamp;
import java.time.Instant;

public final class SqlTime {
  private SqlTime() {
  }

  public static Timestamp from(Instant value) {
    return value == null ? null : Timestamp.from(value);
  }
}
