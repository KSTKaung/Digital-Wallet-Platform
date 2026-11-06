package com.portfolio.wallet.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * One PostgreSQL container per test JVM, provisioned like the Compose database: the same role init
 * script ({@code infra/postgres/init/01-create-roles.sh}) creates {@code wallet_owner} and {@code
 * wallet_app} (documentations/06 MVP-ADR-02).
 *
 * <p>The application connects as {@code wallet_app} and Flyway as {@code wallet_owner}; nothing in
 * the application uses the container's bootstrap superuser (only {@link
 * #openBootstrapSuperuserConnection()} does, for test fixtures). Integration tests register it
 * with:
 *
 * <pre>{@code
 * @DynamicPropertySource
 * static void database(DynamicPropertyRegistry registry) {
 *   PostgresTestDatabase.register(registry);
 * }
 * }</pre>
 *
 * The container is started on first use and stopped by Testcontainers when the JVM exits, so every
 * Spring context in the run shares one database.
 */
public final class PostgresTestDatabase {

  public static final String APP_ROLE = "wallet_app";
  public static final String OWNER_ROLE = "wallet_owner";

  /** Relative to {@code backend/}, the working directory of Maven's test plugins. */
  private static final Path INIT_SCRIPT = Path.of("..", "infra", "postgres", "init");

  private static final String SCRIPT_NAME = "01-create-roles.sh";

  // Test-only passwords, new for every run; never used outside the throwaway container.
  private static final String OWNER_PASSWORD = UUID.randomUUID().toString();
  private static final String APP_PASSWORD = UUID.randomUUID().toString();

  private static final int UNTRIPPABLE_LIMIT = 1_000_000;

  /** Test database only; see {@link #createContainer()}. */
  private static final int MAX_CONNECTIONS = 300;

  private static final PostgreSQLContainer CONTAINER = createContainer();

  private PostgresTestDatabase() {}

  /**
   * Registers the datasource ({@code wallet_app}) and Flyway ({@code wallet_owner}) properties, and
   * the required secrets: every full application context in the tests starts through this method.
   */
  public static void register(DynamicPropertyRegistry registry) {
    registerWithRealLimits(registry);
    // Raised so that no flow test trips the limiter by accident; the limiter has its own tests.
    registry.add("app.rate-limit.auth-requests-per-minute-per-ip", () -> UNTRIPPABLE_LIMIT);
    registry.add("app.rate-limit.other-requests-per-minute-per-user", () -> UNTRIPPABLE_LIMIT);
    registry.add("app.rate-limit.money-requests-per-minute-per-customer", () -> UNTRIPPABLE_LIMIT);
    registry.add("app.auth.purge.enabled", () -> false);
  }

  /** For a test that sets its own limits: dynamic properties outrank the test's inline ones. */
  public static void registerWithRealLimits(DynamicPropertyRegistry registry) {
    start();
    registry.add("spring.datasource.url", CONTAINER::getJdbcUrl);
    registry.add("spring.datasource.username", () -> APP_ROLE);
    registry.add("spring.datasource.password", () -> APP_PASSWORD);
    registry.add("spring.flyway.user", () -> OWNER_ROLE);
    registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
    registry.add("spring.flyway.placeholders[app_role]", () -> APP_ROLE);
    // Tests drive the outbox dispatcher themselves, so no background run races them.
    registry.add("app.notification.outbox.enabled", () -> false);
    // Tests call the idempotency purge themselves, for the same reason.
    registry.add("app.wallet.idempotency.purge.enabled", () -> false);
    registry.add("app.transfer.idempotency.purge.enabled", () -> false);
    registry.add("app.wallet.lookup.purge.enabled", () -> false);
    TestSecrets.values().forEach((name, value) -> registry.add(name, () -> value));
  }

  /** Test fixtures only: a connection as the container's bootstrap superuser. */
  public static Connection openBootstrapSuperuserConnection() throws SQLException {
    start();
    return DriverManager.getConnection(
        CONTAINER.getJdbcUrl(), CONTAINER.getUsername(), CONTAINER.getPassword());
  }

  private static synchronized void start() {
    if (!CONTAINER.isRunning()) {
      CONTAINER.start();
    }
  }

  private static PostgreSQLContainer createContainer() {
    Path script = INIT_SCRIPT.resolve(SCRIPT_NAME).toAbsolutePath().normalize();
    if (!Files.isRegularFile(script)) {
      throw new IllegalStateException("Role init script not found: " + script);
    }
    return new PostgreSQLContainer("postgres:18.6-alpine")
        // Each cached Spring test context keeps its own connection pool open, so the suite needs
        // more than PostgreSQL's default of 100 connections. fsync=off is Testcontainers' default
        // command, kept because setting a command replaces it.
        .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=" + MAX_CONNECTIONS)
        .withEnv("WALLET_OWNER_PASSWORD", OWNER_PASSWORD)
        .withEnv("WALLET_APP_PASSWORD", APP_PASSWORD)
        .withCopyFileToContainer(
            MountableFile.forHostPath(script, 0755), "/docker-entrypoint-initdb.d/" + SCRIPT_NAME);
  }
}
