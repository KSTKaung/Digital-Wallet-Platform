package com.portfolio.wallet.auth.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/** The PostgreSQL driver binds {@link OffsetDateTime}, not {@link Instant}. */
final class Timestamps {

  private Timestamps() {}

  static OffsetDateTime bind(Instant instant) {
    return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
  }

  static Instant read(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
