package com.portfolio.wallet.transfer.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * The PostgreSQL driver reads and binds {@code timestamptz} as {@link OffsetDateTime}, not {@link
 * Instant}. The transfer module's own copy: module persistence code is not shared.
 */
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
