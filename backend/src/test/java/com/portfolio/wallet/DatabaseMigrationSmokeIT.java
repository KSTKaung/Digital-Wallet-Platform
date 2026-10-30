package com.portfolio.wallet;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.support.PostgresTestDatabase;
import java.util.Arrays;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationInfoService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Migration smoke test, outside the {@code database} suite. Beyond {@code MigrationAndRoleIT} it
 * pins the exact applied script versions (V1 to V6, in order, current version 6) and that Flyway's
 * history lives in the {@code flyway} schema (doc 08 Q6), so an added, removed or renumbered
 * migration fails here. Its runtime-role check duplicates {@code MigrationAndRoleIT}; unlike that
 * suite it runs in its own Spring context, not the shared {@code DatabaseIntegrationTest} one.
 */
@SpringBootTest
class DatabaseMigrationSmokeIT {

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    PostgresTestDatabase.register(registry);
  }

  @Autowired Flyway flyway;
  @Autowired JdbcTemplate jdbc;

  @Test
  void migrationsV1ToV6AreAppliedInTheFlywaySchema() {
    MigrationInfoService info = flyway.info();

    assertThat(info.pending()).isEmpty();
    assertThat(info.current().getVersion().getVersion()).isEqualTo("6");
    assertThat(
            Arrays.stream(info.applied())
                // Skips Flyway's own "create schema" marker row.
                .filter(m -> m.getScript().endsWith(".sql"))
                .map(MigrationInfo::getVersion)
                .map(v -> v.getVersion()))
        .containsExactly("1", "2", "3", "4", "5", "6");
    assertThat(Arrays.stream(info.applied()).allMatch(m -> m.getState().isApplied())).isTrue();
    assertThat(Arrays.stream(info.applied()).noneMatch(m -> m.getState().isFailed())).isTrue();
    assertThat(flyway.getConfiguration().getDefaultSchema()).isEqualTo("flyway");
  }

  @Test
  void applicationConnectsAsNonSuperuserRuntimeRole() {
    assertThat(jdbc.queryForObject("SELECT current_user", String.class))
        .isEqualTo(PostgresTestDatabase.APP_ROLE);
    assertThat(
            jdbc.queryForObject(
                "SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean.class))
        .isFalse();
  }
}
