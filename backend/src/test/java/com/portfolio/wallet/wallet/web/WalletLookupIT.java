package com.portfolio.wallet.wallet.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.portfolio.wallet.WalletApplication;
import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.RateLimitedException;
import com.portfolio.wallet.support.PostgresTestDatabase;
import com.portfolio.wallet.wallet.application.LookupPurgeService;
import com.portfolio.wallet.wallet.application.RecipientNames;
import com.portfolio.wallet.wallet.application.WalletLookupService;
import com.portfolio.wallet.wallet.application.WalletNumberGenerator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import tools.jackson.databind.JsonNode;

/**
 * Recipient lookup by wallet number (documentations/02 §2.3; doc 06 MVP-ADR-19, -20; doc 08 §4B.2
 * Q3, §4B.4 AC 10, 11). Every test uses fresh accounts and its own client IP, because the per-IP
 * count is shared by everything the test JVM sends from one address.
 */
class WalletLookupIT extends WalletFlowSupport {

  static final String LOOKUP = "/api/v1/wallets/lookup";

  @Autowired WalletLookupService lookups;
  @Autowired LookupPurgeService purge;
  @Autowired RecipientNames recipientNames;

  private static String randomIp() {
    ThreadLocalRandom random = ThreadLocalRandom.current();
    return "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
  }

  private MockHttpServletResponse lookup(Account caller, String walletNumber, String ip)
      throws Exception {
    var request = get(LOOKUP);
    if (walletNumber != null) {
      request.param("walletNumber", walletNumber);
    }
    request.with(
        r -> {
          r.setRemoteAddr(ip);
          return r;
        });
    return send(request, caller == null ? null : caller.token());
  }

  private String numberOf(Account account) {
    return (String) walletOf(account.id()).get("wallet_number");
  }

  private int attempts(UUID userId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM wallet.lookup_attempts WHERE user_id = ?", Integer.class, userId);
  }

  private List<Map<String, Object>> lookupAudits(UUID userId) {
    return jdbc.queryForList(
        "SELECT result, resource, resource_id, host(client_ip) AS ip, new_value::text AS new_value"
            + " FROM audit.audit_logs WHERE actor_id = ? AND action = 'WALLET_LOOKUP' ORDER BY id",
        userId);
  }

  private static String absentNumber() {
    return new WalletNumberGenerator().next().value();
  }

  // Found
  // -------------------------------------------------------------------------------------------

  @Test
  void anExactMatchReturnsOnlyTheMaskedNameAndNumber() throws Exception {
    Account caller = withWallet();
    Account recipient = withWallet();
    String number = numberOf(recipient);
    String ip = randomIp();

    MockHttpServletResponse response = lookup(caller, number, ip);

    assertThat(response.getStatus()).isEqualTo(200);
    JsonNode body = body(response);
    assertThat(body.propertyNames()).containsExactlyInAnyOrder("maskedName", "maskedWalletNumber");
    assertThat(body.get("maskedName").asString()).isEqualTo("Somchai J.");
    assertThat(body.get("maskedWalletNumber").asString())
        .isEqualTo("*******" + number.substring(7));
    assertThat(response.getContentAsString())
        .doesNotContain("Jaidee")
        .doesNotContain(number)
        .doesNotContain(recipient.id().toString());

    List<Map<String, Object>> audits = lookupAudits(caller.id());
    assertThat(audits).hasSize(1);
    assertThat(audits.get(0))
        .containsEntry("result", "SUCCESS")
        .containsEntry("resource", "WALLET")
        .containsEntry("resource_id", walletOf(recipient.id()).get("id").toString())
        .containsEntry("ip", ip);
    assertThat((String) audits.get(0).get("new_value"))
        .doesNotContain(number)
        .doesNotContain("Somchai")
        .doesNotContain("Jaidee");
  }

  @Test
  void q3TheLegalNameComesFromTheUserModuleThroughTheWalletInterface() throws Exception {
    assertThat(recipientNames.getClass().getName())
        .startsWith("com.portfolio.wallet.user.application.");
    Account recipient = withWallet();
    assertThat(recipientNames.maskedLegalName(recipient.id())).contains("Somchai J.");
    assertThat(recipientNames.maskedLegalName(UUID.randomUUID())).isEmpty();
    // Before approval there is no legal name on the profile.
    Account pending = customer();
    submitted(pending, "BASIC");
    assertThat(recipientNames.maskedLegalName(pending.id())).isEmpty();
  }

  // Not found
  // ---------------------------------------------------------------------------------------

  @Test
  void everyNonEligibleWalletGivesTheIdenticalNotFound() throws Exception {
    Account caller = withWallet();
    String ip = randomIp();
    Account frozen = withWallet();
    assertThat(postAs(FREEZE, null, frozen.token()).getStatus()).isEqualTo(200);
    Account closed = withWallet();
    jdbc.update("UPDATE wallet.wallets SET status = 'CLOSED' WHERE customer_id = ?", closed.id());
    String recipient = numberOf(withWallet());
    char last = recipient.charAt(10);
    String neighbour = recipient.substring(0, 10) + (last == '9' ? '0' : (char) (last + 1));

    List<String> numbers =
        List.of(absentNumber(), numberOf(frozen), numberOf(closed), numberOf(caller), neighbour);
    List<String> bodies = new ArrayList<>();
    for (String number : numbers) {
      MockHttpServletResponse response = lookup(caller, number, ip);
      assertThat(response.getStatus()).as(number).isEqualTo(404);
      assertThat(errorCode(response)).isEqualTo("NOT_FOUND");
      assertThat(response.getContentAsString()).doesNotContain(number);
      bodies.add(stable(response));
    }
    assertThat(bodies).containsOnly(bodies.get(0));
    // The same body as any other missing resource (MVP-ADR-19).
    assertThat(bodies.get(0)).isEqualTo(stable(getAs("/api/v1/no-such-thing", caller.token())));

    List<Map<String, Object>> audits = lookupAudits(caller.id());
    assertThat(audits).hasSize(numbers.size());
    assertThat(audits).allSatisfy(row -> assertThat(row).containsEntry("result", "FAILURE"));
    assertThat(audits).allSatisfy(row -> assertThat(row.get("resource_id")).isNull());
    assertThat(attempts(caller.id())).isEqualTo(numbers.size());
  }

  /**
   * X2: a wrong check digit passes the format step, is counted and audited like a missing wallet,
   * and answers the byte-identical 404.
   */
  @Test
  void aWrongCheckDigitIsTheIdenticalNotFoundCountedAndAudited() throws Exception {
    Account caller = withWallet();
    String ip = randomIp();
    String real = numberOf(withWallet());
    MockHttpServletResponse missing = lookup(caller, absentNumber(), ip);

    List<String> wrong = new ArrayList<>();
    for (int digit = 0; digit <= 9; digit++) {
      String candidate = real.substring(0, 10) + digit;
      if (!candidate.equals(real)) {
        wrong.add(candidate);
      }
    }
    for (String number : wrong) {
      MockHttpServletResponse response = lookup(caller, number, ip);
      assertThat(response.getStatus()).as(number).isEqualTo(404);
      assertThat(stable(response)).isEqualTo(stable(missing));
    }

    // 1 missing + 9 wrong check digits = the user's 10; the next lookup is refused.
    assertThat(attempts(caller.id())).isEqualTo(10);
    List<Map<String, Object>> audits = lookupAudits(caller.id());
    assertThat(audits).hasSize(10);
    assertThat(audits)
        .allSatisfy(
            row -> {
              assertThat(row).containsEntry("result", "FAILURE");
              assertThat((String) row.get("new_value")).contains("NOT_FOUND");
            });
    assertThat(lookup(caller, real, ip).getStatus()).isEqualTo(429);
  }

  @Test
  void onlyElevenDigitsAreLookedUpAndBadInputIsNotCounted() throws Exception {
    Account caller = withWallet();
    String number = numberOf(withWallet());
    String ip = randomIp();

    for (String bad : List.of(number.substring(1), number + "0", " " + number, "abcdefghijk")) {
      MockHttpServletResponse response = lookup(caller, bad, ip);
      assertThat(response.getStatus()).as(bad).isEqualTo(400);
      assertThat(errorCode(response)).isEqualTo("VALIDATION_ERROR");
      assertThat(response.getContentAsString()).doesNotContain(number);
    }
    MockHttpServletResponse missing = lookup(caller, null, ip);
    assertThat(missing.getStatus()).isEqualTo(400);
    assertThat(attempts(caller.id())).isZero();
    assertThat(lookupAudits(caller.id())).isEmpty();
  }

  // The caller
  // -------------------------------------------------------------------------------------

  @Test
  void onlyACustomerWithAnActiveWalletCanLookUp() throws Exception {
    String number = numberOf(withWallet());
    String ip = randomIp();

    Account noWallet = customer();
    MockHttpServletResponse kyc = lookup(noWallet, number, ip);
    assertThat(kyc.getStatus()).isEqualTo(403);
    assertThat(errorCode(kyc)).isEqualTo("KYC_REQUIRED");

    Account frozen = withWallet();
    assertThat(postAs(FREEZE, null, frozen.token()).getStatus()).isEqualTo(200);
    MockHttpServletResponse refused = lookup(frozen, number, ip);
    assertThat(refused.getStatus()).isEqualTo(409);
    assertThat(errorCode(refused)).isEqualTo("WALLET_FROZEN");
    assertThat(attempts(frozen.id())).isZero();

    for (Role role : List.of(Role.ADMIN, Role.COMPLIANCE_OFFICER)) {
      assertThat(lookup(account(role), number, ip).getStatus()).as(role.name()).isEqualTo(403);
    }
    MockHttpServletResponse anonymous = lookup(null, number, ip);
    assertThat(anonymous.getStatus()).isEqualTo(401);
  }

  // Rate limits
  // ------------------------------------------------------------------------------------

  @Test
  void tenLookupsPerUserPerMinuteThen429WithRetryAfter() throws Exception {
    Account caller = withWallet();
    String found = numberOf(withWallet());
    String ip = randomIp();

    for (int i = 0; i < 10; i++) {
      boolean even = i % 2 == 0;
      int status = lookup(caller, even ? found : absentNumber(), ip).getStatus();
      assertThat(status).as("lookup " + i).isEqualTo(even ? 200 : 404);
    }
    MockHttpServletResponse limited = lookup(caller, found, ip);

    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(errorCode(limited)).isEqualTo("RATE_LIMITED");
    assertThat(Long.parseLong(limited.getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1L, 60L);
    // A refused request is neither counted nor audited.
    assertThat(attempts(caller.id())).isEqualTo(10);
    assertThat(lookupAudits(caller.id())).hasSize(10);
    // Another user on another address is not affected.
    assertThat(lookup(withWallet(), found, randomIp()).getStatus()).isEqualTo(200);
  }

  @Test
  void thirtyLookupsPerIpPerMinuteAcrossUsers() throws Exception {
    String found = numberOf(withWallet());
    String ip = randomIp();
    for (int user = 0; user < 3; user++) {
      Account caller = withWallet();
      for (int i = 0; i < 10; i++) {
        assertThat(lookup(caller, found, ip).getStatus()).isEqualTo(200);
      }
    }
    Account fresh = withWallet();

    MockHttpServletResponse limited = lookup(fresh, found, ip);
    assertThat(limited.getStatus()).isEqualTo(429);
    assertThat(limited.getHeader(HttpHeaders.RETRY_AFTER)).isNotNull();
    assertThat(lookup(fresh, found, randomIp()).getStatus()).isEqualTo(200);
  }

  @Test
  void anIpv6SubscriberCountsAsOneAddressPerSlash64() throws Exception {
    String found = numberOf(withWallet());
    String prefix =
        "2001:db8:" + Integer.toHexString(ThreadLocalRandom.current().nextInt(1, 0xffff)) + ":7:";
    for (int user = 0; user < 3; user++) {
      Account caller = withWallet();
      for (int i = 0; i < 10; i++) {
        assertThat(
                lookup(caller, found, prefix + Integer.toHexString(user * 16 + i + 1) + "::1")
                    .getStatus())
            .isEqualTo(200);
      }
    }
    assertThat(lookup(withWallet(), found, prefix + "ffff::9").getStatus()).isEqualTo(429);
  }

  /** AC 10: the counts are in PostgreSQL, so a freshly started application still refuses. */
  @Test
  void theLimitHoldsInANewApplicationContext() throws Exception {
    Account caller = withWallet();
    String found = numberOf(withWallet());
    String ip = randomIp();
    for (int i = 0; i < 10; i++) {
      assertThat(lookup(caller, found, ip).getStatus()).isEqualTo(200);
    }

    Map<String, Object> properties = new LinkedHashMap<>();
    PostgresTestDatabase.register(
        new DynamicPropertyRegistry() {
          @Override
          public void add(String name, Supplier<Object> valueSupplier) {
            properties.put(name, valueSupplier.get());
          }
        });
    properties.put("server.port", 0);
    // Y6: the second context may take a while to start; a one-hour window keeps the ten attempts
    // above inside it however long that takes. The counts still come only from the database.
    properties.put("app.wallet.lookup.window", "1h");
    List<String> args = new ArrayList<>();
    properties.forEach((name, value) -> args.add("--" + name + "=" + value));
    try (ConfigurableApplicationContext restarted =
        new SpringApplicationBuilder(WalletApplication.class).run(args.toArray(String[]::new))) {
      WalletLookupService fresh = restarted.getBean(WalletLookupService.class);
      assertThat(fresh).isNotSameAs(lookups);
      assertThatThrownBy(() -> fresh.lookup(caller.id(), found, ip))
          .isInstanceOf(RateLimitedException.class);
    }
  }

  /** No lost increments: of many simultaneous lookups by one user, exactly 10 are let through. */
  @Test
  void concurrentLookupsNeverExceedTheLimit() throws Exception {
    Account caller = withWallet();
    String found = numberOf(withWallet());
    String ip = randomIp();
    int threads = 20;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    try {
      CyclicBarrier start = new CyclicBarrier(threads);
      List<Future<String>> outcomes = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        outcomes.add(
            pool.submit(
                () -> {
                  start.await(30, TimeUnit.SECONDS);
                  try {
                    lookups.lookup(caller.id(), found, ip);
                    return "OK";
                  } catch (ApiException e) {
                    return e.errorCode().name();
                  }
                }));
      }
      List<String> results = new ArrayList<>();
      for (Future<String> outcome : outcomes) {
        results.add(outcome.get(60, TimeUnit.SECONDS));
      }
      assertThat(results).filteredOn("OK"::equals).hasSize(10);
      assertThat(results).filteredOn("RATE_LIMITED"::equals).hasSize(10);
    } finally {
      pool.shutdownNow();
    }
    assertThat(attempts(caller.id())).isEqualTo(10);
  }

  // Purge
  // -------------------------------------------------------------------------------------------

  @Test
  void thePurgeDeletesOnlyAttemptsOutsideTheWindow() throws Exception {
    UUID oldUser = UUID.randomUUID();
    UUID recentUser = UUID.randomUUID();
    // The trigger stamps attempted_at, so a past attempt needs the triggers off (test fixture).
    withTriggersDisabled(
        superuser ->
            update(
                superuser,
                "INSERT INTO wallet.lookup_attempts (user_id, client_ip, attempted_at)"
                    + " VALUES (?, '192.0.2.200', now() - interval '2 minutes')",
                oldUser));
    jdbc.update(
        "INSERT INTO wallet.lookup_attempts (user_id, client_ip) VALUES (?, '192.0.2.201')",
        recentUser);

    assertThat(purge.purgeExpired()).isGreaterThanOrEqualTo(1);

    assertThat(attempts(oldUser)).isZero();
    assertThat(attempts(recentUser)).isEqualTo(1);
  }
}
