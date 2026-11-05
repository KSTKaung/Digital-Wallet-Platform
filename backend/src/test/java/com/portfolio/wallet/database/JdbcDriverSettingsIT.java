package com.portfolio.wallet.database;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.sql.Connection;
import org.junit.jupiter.api.Test;
import org.postgresql.PGConnection;
import org.postgresql.jdbc.AutoSave;

/**
 * Driver settings of the application's pool (documentations/06 MVP-ADR-02 limitations; doc 08 §4B.4
 * AC 12): {@code autosave} stays off, so the driver never retries a failed statement inside the
 * money path behind a silent savepoint.
 */
class JdbcDriverSettingsIT extends DatabaseIntegrationTest {

  @Test
  void autosaveIsOff() throws Exception {
    try (Connection connection = dataSource.getConnection()) {
      assertThat(connection.unwrap(PGConnection.class).getAutosave()).isEqualTo(AutoSave.NEVER);
    }
  }
}
