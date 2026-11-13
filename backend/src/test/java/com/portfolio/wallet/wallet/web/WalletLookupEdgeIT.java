package com.portfolio.wallet.wallet.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.portfolio.wallet.support.PostgresTestDatabase;
import com.portfolio.wallet.wallet.application.WalletNumberGenerator;
import com.portfolio.wallet.wallet.persistence.LookupAttemptRepository;
import com.portfolio.wallet.wallet.persistence.LookupAttemptRepository.WindowCount;
import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Wallet lookup edges not covered by {@link WalletLookupIT} (documentations/02 §2.3; doc 06
 * MVP-ADR-20; doc 08 §4B.4 AC 10; X1–X6): a failure after the attempt was counted rolls the attempt
 * and its audit row back, and the boundary of the one-minute sliding window.
 */
class WalletLookupEdgeIT extends MoneyFlowSupport {

  static final String LOOKUP = "/api/v1/wallets/lookup";

  @org.springframework.beans.factory.annotation.Autowired
  com.portfolio.wallet.wallet.persistence.WalletRepository walletRepository;

  @org.springframework.beans.factory.annotation.Autowired LookupAttemptRepository attemptRepository;

  @org.springframework.beans.factory.annotation.Autowired
  com.portfolio.wallet.wallet.application.RecipientNames recipientNames;

  @org.springframework.beans.factory.annotation.Autowired
  com.portfolio.wallet.audit.application.AuditService auditService;

  @org.springframework.beans.factory.annotation.Autowired
  org.springframework.transaction.PlatformTransactionManager transactionManager;

  @org.springframework.beans.factory.annotation.Autowired
  com.portfolio.wallet.common.database.TransactionTimeouts timeouts;

  private static String randomIp() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
  }

  private MockHttpServletResponse lookup(Account caller, String walletNumber, String ip)
      throws Exception {
    return send(
        get(LOOKUP)
            .param("walletNumber", walletNumber)
            .with(
                r -> {
                  r.setRemoteAddr(ip);
                  return r;
                }),
        caller.token());
  }

  private String numberOf(Account account) {
    return (String) walletOf(account.id()).get("wallet_number");
  }

  private int attemptsOfUser(UUID userId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM wallet.lookup_attempts WHERE user_id = ?", Integer.class, userId);
  }

  private int attemptsOfIp(String ip) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM wallet.lookup_attempts WHERE client_ip = CAST(? AS inet)",
        Integer.class,
        ip);
  }

  private int lookupAudits(UUID userId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit.audit_logs WHERE actor_id = ? AND action = 'WALLET_LOOKUP'",
        Integer.class,
        userId);
  }

  // Failure after counting --------------------------------------------------------------------

  /**
   * The attempt is recorded, the name read and the audit row written; then the transaction fails.
   * Nothing of it stays: the failed lookup does not use up the caller's or the address's count, and
   * the retry is counted once.
   */
  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void aFailureAfterTheAttemptIsCountedRollsTheAttemptBack(boolean found) throws Exception {
    Account caller = withWallet();
    String number = found ? numberOf(withWallet()) : new WalletNumberGenerator().next().value();
    String ip = randomIp();
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("injected failure");
            })
        .when(auditRowsSpy)
        .insert(argThat(row -> row != null && row.action().equals("WALLET_LOOKUP")));

    MockHttpServletResponse failed = lookup(caller, number, ip);

    assertThat(failed.getStatus()).isEqualTo(500);
    assertThat(errorCode(failed)).isEqualTo("SYSTEM_ERROR");
    assertThat(failed.getContentAsString())
        .doesNotContain("injected")
        .doesNotContain("Somchai")
        .doesNotContain(number.substring(7));
    assertThat(attemptsOfUser(caller.id())).isZero();
    assertThat(attemptsOfIp(ip)).isZero();
    assertThat(lookupAudits(caller.id())).isZero();

    reset(auditRowsSpy);
    MockHttpServletResponse retry = lookup(caller, number, ip);
    assertThat(retry.getStatus()).isEqualTo(found ? 200 : 404);
    assertThat(attemptsOfUser(caller.id())).isEqualTo(1);
    assertThat(lookupAudits(caller.id())).isEqualTo(1);
  }

  /** Ten failed lookups leave the full limit: the eleventh real one is still let through. */
  @Test
  void failedLookupsDoNotUseUpTheLimit() throws Exception {
    Account caller = withWallet();
    String number = numberOf(withWallet());
    String ip = randomIp();
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("injected failure");
            })
        .when(auditRowsSpy)
        .insert(argThat(row -> row != null && row.action().equals("WALLET_LOOKUP")));
    for (int i = 0; i < 10; i++) {
      assertThat(lookup(caller, number, ip).getStatus()).isEqualTo(500);
    }

    reset(auditRowsSpy);
    for (int i = 0; i < 10; i++) {
      assertThat(lookup(caller, number, ip).getStatus()).isEqualTo(200);
    }
    assertThat(lookup(caller, number, ip).getStatus()).isEqualTo(429);
  }

  // Window boundary ---------------------------------------------------------------------------

  /**
   * The window and the purge measure from {@code statement_timestamp()}, each statement's own start
   * (Y1), so the boundary is checked with a margin of a second on each side rather than to the
   * microsecond: an attempt older than the window no longer counts and is purged; a younger one
   * counts, is kept, and its {@code Retry-After} is the whole seconds until it leaves.
   */
  @Test
  void attemptsOlderThanTheWindowNoLongerCountAndArePurged() throws Exception {
    UUID user = UUID.randomUUID();
    String ip = "198.51.100." + ThreadLocalRandom.current().nextInt(1, 255);
    try (Connection superuser = PostgresTestDatabase.openBootstrapSuperuserConnection()) {
      superuser.setAutoCommit(false);
      try {
        // The trigger stamps attempted_at, so past attempts need it off (test fixture).
        update(superuser, "SET LOCAL session_replication_role = replica");
        for (String age : List.of("61 seconds", "59 seconds")) {
          update(
              superuser,
              "INSERT INTO wallet.lookup_attempts (user_id, client_ip, attempted_at)"
                  + " VALUES (?, CAST(? AS inet), statement_timestamp() - CAST(? AS interval))",
              user,
              ip,
              age);
        }
        LookupAttemptRepository attempts =
            new LookupAttemptRepository(
                JdbcClient.create(new SingleConnectionDataSource(superuser, true)));

        WindowCount byUser = attempts.countForUser(user, 60);
        WindowCount byIp = attempts.countForIp(ip, 60);
        assertThat(byUser.count()).isEqualTo(1);
        assertThat(byIp.count()).isEqualTo(1);
        assertThat(byUser.secondsUntilOldestLeaves()).isEqualTo(1);

        attempts.deleteOlderThan(60, Integer.MAX_VALUE);
        assertThat(
                queryOne(
                    superuser,
                    "SELECT count(*) FROM wallet.lookup_attempts WHERE user_id = ?",
                    Long.class,
                    user))
            .as("only the attempt that still counts remains")
            .isEqualTo(1L);
      } finally {
        superuser.rollback();
      }
    }
  }

  /**
   * End to end: ten attempts that leave the window in a few seconds refuse the next lookup with a
   * short {@code Retry-After}; after waiting that long the caller is let through. Seeded at 55
   * seconds, so a slow machine still sees a value between 1 and 5 (Y6).
   */
  @Test
  void aCallerWhoWaitsRetryAfterIsLetThrough() throws Exception {
    Account caller = withWallet();
    String number = numberOf(withWallet());
    String ip = randomIp();
    withTriggersDisabled(
        superuser -> {
          for (int i = 0; i < 10; i++) {
            update(
                superuser,
                "INSERT INTO wallet.lookup_attempts (user_id, client_ip, attempted_at)"
                    + " VALUES (?, CAST(? AS inet), statement_timestamp() - interval '55 seconds')",
                caller.id(),
                ip);
          }
        });

    MockHttpServletResponse limited = lookup(caller, number, ip);
    assertThat(limited.getStatus()).isEqualTo(429);
    long retryAfter = Long.parseLong(limited.getHeader(HttpHeaders.RETRY_AFTER));
    assertThat(retryAfter).isBetween(1L, 5L);

    Thread.sleep(retryAfter * 1000);
    assertThat(lookup(caller, number, ip).getStatus()).isEqualTo(200);
  }

  /** Y7: both limits trip at once; the longer of the two waits is the {@code Retry-After}. */
  @Test
  void whenBothLimitsTripTheLongerWaitWins() throws Exception {
    Account caller = withWallet();
    String number = numberOf(withWallet());
    String ip = randomIp();
    withTriggersDisabled(
        superuser -> {
          // The caller's ten attempts leave the window in about 10 s, from another address.
          for (int i = 0; i < 10; i++) {
            update(
                superuser,
                "INSERT INTO wallet.lookup_attempts (user_id, client_ip, attempted_at)"
                    + " VALUES (?, CAST('203.0.113.9' AS inet),"
                    + " statement_timestamp() - interval '50 seconds')",
                caller.id());
          }
          // Thirty attempts of other users on this address leave it in about 40 s.
          for (int i = 0; i < 30; i++) {
            update(
                superuser,
                "INSERT INTO wallet.lookup_attempts (user_id, client_ip, attempted_at)"
                    + " VALUES (?, CAST(? AS inet), statement_timestamp() - interval '20 seconds')",
                UUID.randomUUID(),
                ip);
          }
        });

    MockHttpServletResponse limited = lookup(caller, number, ip);

    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(Long.parseLong(limited.getHeader(HttpHeaders.RETRY_AFTER))).isBetween(35L, 40L);
  }

  /**
   * Y1: the counts and the attempt's stamp are taken after the advisory lock is acquired. One
   * attempt sits 58 seconds inside a one-attempt limit; the lookup waits on the user lock for over
   * two seconds. Measured from the transaction's start the seeded attempt would still count and the
   * lookup would be refused; measured after the wait it has left the window, so the lookup goes
   * through and its own attempt is stamped after the release.
   */
  @Test
  void countsAndStampAreTakenAfterTheLockWait() throws Exception {
    Account caller = withWallet();
    String number = numberOf(withWallet());
    String ip = randomIp();
    var strict =
        new com.portfolio.wallet.wallet.application.WalletLookupService(
            walletRepository,
            attemptRepository,
            recipientNames,
            auditService,
            new com.portfolio.wallet.wallet.application.LookupProperties(
                1,
                30,
                java.time.Duration.ofMinutes(1),
                new com.portfolio.wallet.wallet.application.LookupProperties.Purge(
                    false, java.time.Duration.ofHours(1), 10)),
            transactionManager,
            timeouts);
    withTriggersDisabled(
        superuser ->
            update(
                superuser,
                "INSERT INTO wallet.lookup_attempts (user_id, client_ip, attempted_at)"
                    + " VALUES (?, CAST(? AS inet), statement_timestamp() - interval '58 seconds')",
                caller.id(),
                ip));

    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    try (Connection holder = openTransaction()) {
      queryOne(
          holder,
          "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))::text",
          String.class,
          "wallet.lookup.user:" + caller.id());
      java.util.concurrent.Future<?> lookup =
          pool.submit(() -> strict.lookup(caller.id(), number, ip));
      awaitBlockedBy(backendPid(holder), 1);
      Thread.sleep(2500);
      java.time.OffsetDateTime released = transactionNow(holder).plusSeconds(2);
      holder.rollback();

      lookup.get(30, java.util.concurrent.TimeUnit.SECONDS);
      java.time.OffsetDateTime stamped =
          jdbc.queryForObject(
              "SELECT max(attempted_at) FROM wallet.lookup_attempts WHERE user_id = ?",
              java.time.OffsetDateTime.class,
              caller.id());
      assertThat(stamped).isAfter(released);
    } finally {
      pool.shutdownNow();
    }
  }
}
