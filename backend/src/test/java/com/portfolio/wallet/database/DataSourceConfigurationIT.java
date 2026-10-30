package com.portfolio.wallet.database;

import static com.portfolio.wallet.support.SqlAssertions.UNIQUE_VIOLATION;
import static com.portfolio.wallet.support.SqlAssertions.assertSqlState;
import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.support.DatabaseIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * PostgreSQL error detail can contain row values, so the application's connections must keep it out
 * of exception messages and logs (application.yaml {@code logServerErrorDetail}).
 */
class DataSourceConfigurationIT extends DatabaseIntegrationTest {

  @Test
  void hikariPassesLogServerErrorDetailFalseWithItsExactKey() throws SQLException {
    assertThat(dataSource.unwrap(HikariDataSource.class).getDataSourceProperties())
        .containsOnlyKeys("logServerErrorDetail")
        .extractingByKey("logServerErrorDetail")
        .hasToString("false");
  }

  @Test
  void uniqueViolationThroughTheAppDataSourceCarriesNoRowValues() throws SQLException {
    Map<String, Object> wallet = validWallet();
    insert("wallet.wallets", wallet);
    Map<String, Object> duplicate =
        with(validWallet(), "wallet_number", wallet.get("wallet_number"));

    SQLException error =
        assertSqlState(UNIQUE_VIOLATION, () -> insert("wallet.wallets", duplicate));

    assertThat(error.getMessage())
        .contains("uq_wallets_wallet_number")
        .doesNotContain("Detail:")
        .doesNotContain((String) wallet.get("wallet_number"));
  }

  /** Control: without the property (Flyway's DataSource) the same error does carry the values. */
  @Test
  void theSameViolationCarriesRowValuesWithoutTheProperty() throws SQLException {
    Map<String, Object> wallet = validWallet();
    insert("wallet.wallets", wallet);
    Map<String, Object> duplicate =
        with(validWallet(), "wallet_number", wallet.get("wallet_number"));

    try (Connection owner = ownerDataSource().getConnection()) {
      SQLException error =
          assertSqlState(UNIQUE_VIOLATION, () -> insert(owner, "wallet.wallets", duplicate));

      assertThat(error.getMessage())
          .contains("Detail:")
          .contains((String) wallet.get("wallet_number"));
    }
  }
}
