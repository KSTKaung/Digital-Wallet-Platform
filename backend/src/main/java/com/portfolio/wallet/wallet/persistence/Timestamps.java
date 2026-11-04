package com.portfolio.wallet.wallet.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * The PostgreSQL driver reads {@code timestamptz} as {@link OffsetDateTime}, not {@link Instant}.
 */
final class Timestamps {

  private Timestamps() {}

  static Instant read(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
