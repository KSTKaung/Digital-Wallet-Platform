package com.portfolio.wallet.auth.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.auth.application.KeyedHasher;
import com.portfolio.wallet.notification.application.EmailKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/** Login and lockout (documentations/06 MVP-ADR-23; doc 08 §4A.5 case 2). */
class LoginFlowIT extends AuthFlowSupport {

  private static final String WRONG = "wrong-wrong-wrong";
  private static final Duration LOCK = Duration.ofMinutes(15);

  @Autowired KeyedHasher hasher;

  private void failLogin(String email, int times) throws Exception {
    for (int i = 0; i < times; i++) {
      assertThat(login(email, WRONG).getStatus()).isEqualTo(401);
    }
  }

  private String loginAuditReason(String email, String password) throws Exception {
    String correlationId = UUID.randomUUID().toString();
    postWithCorrelation("login", Map.of("email", email, "password", password), correlationId);
    return auditReason("LOGIN", correlationId);
  }

  @Test
  void wrongUnknownLockedAndInactiveAreTheSame401() throws Exception {
    String wrong = activeUser();
    String locked = activeUser();
    failLogin(locked, 5);
    String pending = email();
    register(pending, phone());
    String frozen = activeUser();
    jdbc.update("UPDATE auth.users SET status = 'FROZEN' WHERE email = ?", frozen);
    String suspended = activeUser();
    jdbc.update("UPDATE auth.users SET status = 'SUSPENDED' WHERE email = ?", suspended);

    String reference = stable(login(wrong, WRONG));
    for (MockHttpServletResponse response :
        new MockHttpServletResponse[] {
          login(email(), PASSWORD),
          login(locked, PASSWORD),
          login(pending, PASSWORD),
          login(frozen, PASSWORD),
          login(suspended, PASSWORD)
        }) {
      assertThat(response.getStatus()).isEqualTo(401);
      assertThat(stable(response)).isEqualTo(reference);
      assertThat(response.getHeader("Set-Cookie")).isNull();
    }
    assertThat(reference).contains("UNAUTHORIZED");
  }

  @Test
  void theAuditKeepsTheRealReason() throws Exception {
    String email = activeUser();
    assertThat(loginAuditReason(email, WRONG)).isEqualTo("WRONG_PASSWORD");
    assertThat(loginAuditReason(email(), PASSWORD)).isEqualTo("UNKNOWN_ACCOUNT");

    String pending = email();
    register(pending, phone());
    assertThat(loginAuditReason(pending, PASSWORD)).isEqualTo("NOT_ACTIVE");

    failLogin(email, 4);
    assertThat(loginAuditReason(email, PASSWORD)).isEqualTo("LOCKED");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_logs WHERE action = 'LOGIN_LOCKOUT'"
                    + " AND resource_id = (SELECT id::text FROM auth.users WHERE email = ?)",
                Integer.class,
                email))
        .isEqualTo(1);
  }

  @Test
  void aSuccessIsAuditedWithItsActor() throws Exception {
    String email = activeUser();
    String correlationId = UUID.randomUUID().toString();
    postWithCorrelation("login", Map.of("email", email, "password", PASSWORD), correlationId);
    assertThat(
            jdbc.queryForMap(
                "SELECT result, actor_type FROM audit.audit_logs"
                    + " WHERE action = 'LOGIN' AND correlation_id = ?",
                correlationId))
        .containsEntry("result", "SUCCESS")
        .containsEntry("actor_type", "CUSTOMER");
  }

  @Test
  void fourFailuresDoNotLockAndASuccessResetsTheCounter() throws Exception {
    String email = activeUser();
    failLogin(email, 4);
    assertThat(login(email, PASSWORD).getStatus()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT failed_login_count FROM auth.credentials c JOIN auth.users u"
                    + " ON u.id = c.user_id WHERE u.email = ?",
                Integer.class,
                email))
        .isZero();

    failLogin(email, 4);
    assertThat(login(email, PASSWORD).getStatus()).isEqualTo(200);
    assertMailCountStays(email, EmailKind.ACCOUNT_LOCKED, 0);
  }

  @Test
  void theFifthFailureLocksEvenForTheRightPasswordUntilExpiryAndEmailsTheOwnerOnce()
      throws Exception {
    String email = activeUser();
    failLogin(email, 5);

    assertThat(login(email, PASSWORD).getStatus()).isEqualTo(401);
    awaitMailCount(email, EmailKind.ACCOUNT_LOCKED, 1);
    failLogin(email, 3);
    assertMailCountStays(email, EmailKind.ACCOUNT_LOCKED, 1);

    clock.advance(LOCK.minusSeconds(1));
    assertThat(login(email, PASSWORD).getStatus()).isEqualTo(401);

    clock.advance(Duration.ofSeconds(1));
    assertThat(login(email, PASSWORD).getStatus()).isEqualTo(200);
  }

  @Test
  void concurrentFailuresCrossingTheThresholdLockOnceAndEmailTheOwnerOnce() throws Exception {
    String email = activeUser();
    int threads = 16;
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Integer>> futures = new ArrayList<>();

    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      for (int i = 0; i < threads; i++) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return login(email, WRONG).getStatus();
                }));
      }
      start.countDown();
      for (Future<Integer> future : futures) {
        assertThat(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isEqualTo(401);
      }
    }

    awaitMailCount(email, EmailKind.ACCOUNT_LOCKED, 1);
    assertMailCountStays(email, EmailKind.ACCOUNT_LOCKED, 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_logs WHERE action = 'LOGIN_LOCKOUT'"
                    + " AND resource_id = (SELECT id::text FROM auth.users WHERE email = ?)",
                Integer.class,
                email))
        .isEqualTo(1);
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT c.failed_login_count, c.locked_until FROM auth.credentials c"
                + " JOIN auth.users u ON u.id = c.user_id WHERE u.email = ?",
            email);
    assertThat(row.get("failed_login_count")).isEqualTo(5);
    assertThat(row.get("locked_until")).isNotNull();
  }

  @Test
  void afterALockExpiresTheCountStartsAgain() throws Exception {
    String email = activeUser();
    failLogin(email, 5);
    clock.advance(LOCK);

    failLogin(email, 4);
    assertThat(login(email, PASSWORD).getStatus()).isEqualTo(200);

    failLogin(email, 5);
    assertThat(login(email, PASSWORD).getStatus()).isEqualTo(401);
    awaitMailCount(email, EmailKind.ACCOUNT_LOCKED, 2);
  }

  @Test
  void unknownEmailsAreCountedUnderAKeyedHashAndLockAndExpireTheSameWay() throws Exception {
    String email = email();
    String hash = hasher.identifier(email);
    failLogin(email, 4);
    assertThat(
            jdbc.queryForMap(
                "SELECT failed_count, locked_until FROM auth.login_failure_counters"
                    + " WHERE identifier_hash = ?",
                hash))
        .containsEntry("failed_count", 4)
        .containsEntry("locked_until", null);

    failLogin(email, 1);
    assertThat(loginAuditReason(email, WRONG)).isEqualTo("LOCKED");

    clock.advance(LOCK);
    assertThat(loginAuditReason(email, WRONG)).isEqualTo("UNKNOWN_ACCOUNT");
    assertThat(
            jdbc.queryForObject(
                "SELECT failed_count FROM auth.login_failure_counters WHERE identifier_hash = ?",
                Integer.class,
                hash))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM auth.login_failure_counters WHERE identifier_hash = ?",
                Integer.class,
                email))
        .isZero();
  }

  @Test
  void anOverlongPasswordIsAnOrdinaryFailure() throws Exception {
    String email = activeUser();
    assertThat(login(email, "x".repeat(10_000)).getStatus()).isEqualTo(401);
  }

  @Test
  void aPendingAccountCountsTowardsTheLockButItsAddressGetsNoEmail() throws Exception {
    String pending = email();
    register(pending, phone());

    failLogin(pending, 5);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_logs WHERE action = 'LOGIN_LOCKOUT'"
                    + " AND resource_id = (SELECT id::text FROM auth.users WHERE email = ?)",
                Integer.class,
                pending))
        .isEqualTo(1);
    assertMailCountStays(pending, EmailKind.ACCOUNT_LOCKED, 0);
  }

  /** The encoder returns at once for an empty input, so these must not skip the hash. */
  @Test
  void anEmptyPasswordIsAnOrdinaryFailure() throws Exception {
    String email = activeUser();
    assertThat(loginAuditReason(email, "")).isEqualTo("WRONG_PASSWORD");
    assertThat(stable(login(email, ""))).isEqualTo(stable(login(email(), PASSWORD)));
  }

  // Malformed input ------------------------------------------------------------------------------

  private void assertNamesTheFieldOnly(
      org.springframework.mock.web.MockHttpServletResponse response, String field, String value)
      throws Exception {
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString())
        .contains("\"errorCode\":\"VALIDATION_ERROR\"", field + ":")
        .doesNotContain(value);
  }

  @Test
  void controlCharactersInALoginEmailAreAValidationErrorNotA500() throws Exception {
    for (String character : List.of("\u0000", "\u0007", "\n", "\u007f", "\u0085")) {
      String value = "someone" + character + "marker@example.test";
      assertNamesTheFieldOnly(
          postJson("login", Map.of("email", value, "password", PASSWORD)), "email", "marker");
    }
  }

  @Test
  void anOverlongLoginEmailIsAValidationError() throws Exception {
    String value = "x".repeat(255);
    assertNamesTheFieldOnly(
        postJson("login", Map.of("email", value, "password", PASSWORD)), "email", value);
    assertThat(
            postJson("login", Map.of("email", "x".repeat(254), "password", PASSWORD)).getStatus())
        .isEqualTo(401);
  }

  @Test
  void controlCharactersInAnIdentifierAreAValidationErrorNotA500() throws Exception {
    String value = "someone\u0000marker@example.test";
    assertNamesTheFieldOnly(
        postJson("resend-otp", Map.of("identifier", value, "purpose", "REGISTRATION")),
        "identifier",
        "marker");
    assertNamesTheFieldOnly(
        postJson(
            "verify-otp", Map.of("identifier", value, "purpose", "REGISTRATION", "otp", "123456")),
        "identifier",
        "marker");
    assertNamesTheFieldOnly(
        postJson("resend-otp", Map.of("identifier", "x".repeat(255), "purpose", "REGISTRATION")),
        "identifier",
        "xxxx");
  }

  @Test
  void nulsInTheOtherEmailFieldsAreRefusedByTheirFormatCheck() throws Exception {
    String value = "someone\u0000marker@example.test";
    assertNamesTheFieldOnly(postJson("forgot-password", Map.of("email", value)), "email", "marker");
    assertNamesTheFieldOnly(
        postJson("register", Map.of("email", value, "phoneNumber", phone(), "password", PASSWORD)),
        "email",
        "marker");
  }

  /**
   * U+0085 and a lone surrogate pass {@code @Email}. Escaped by hand, as Jackson would not. Each
   * body is valid but for the character, and no account may result.
   */
  @ParameterizedTest
  @ValueSource(strings = {"\\u0085", "\\ud800"})
  void whatEmailFormatAdmitsIsStillAValidationErrorOnRegisterAndForgot(String escaped)
      throws Exception {
    String marker = UUID.randomUUID().toString();
    String value = marker + escaped + "@example.test";
    for (String[] call :
        List.of(
            new String[] {
              "register",
              "{\"email\":\"%s\",\"phoneNumber\":\"%s\",\"password\":\"%s\"}"
                  .formatted(value, phone(), PASSWORD)
            },
            new String[] {"forgot-password", "{\"email\":\"%s\"}".formatted(value)})) {
      MockHttpServletResponse response =
          mvc.perform(
                  org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                          API + call[0])
                      .contentType("application/json")
                      .content(call[1]))
              .andReturn()
              .getResponse();
      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(response.getContentAsString())
          .contains("\"errorCode\":\"VALIDATION_ERROR\"", "email:")
          .doesNotContain(marker);
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM auth.users WHERE email LIKE ?", Integer.class, marker + "%"))
        .isZero();
  }

  @Test
  void aNulInAPasswordReachesNothingThatBreaks() throws Exception {
    assertThat(login(activeUser(), "correct-horse\u0000battery").getStatus()).isEqualTo(401);

    String email = email();
    String password = "correct-horse\u0000battery";
    assertThat(registerWith(email, phone(), password).getStatus()).isEqualTo(201);
    assertThat(verifyWith(email, awaitOtp(email, 1), password).getStatus()).isEqualTo(200);
    assertThat(login(email, password).getStatus()).isEqualTo(200);
  }

  /** A lone surrogate cannot be encoded for the hash. Escaped by hand, as Jackson would not. */
  @Test
  void aPasswordThatIsNotValidTextIsAValidationErrorNotA500() throws Exception {
    MockHttpServletResponse response =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        API + "login")
                    .contentType("application/json")
                    .content(
                        "{\"email\":\"someone@example.test\",\"password\":\"long-enough-\\ud800\"}"))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("VALIDATION_ERROR", "password:");
  }

  @Test
  void aLoginWithAStaleBearerTokenStillWorks() throws Exception {
    String email = activeUser();
    MockHttpServletResponse response =
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        API + "login")
                    .header("Authorization", "Bearer not-a-token")
                    .contentType("application/json")
                    .content(
                        jsonMapper.writeValueAsString(
                            Map.of("email", email, "password", PASSWORD))))
            .andReturn()
            .getResponse();
    assertThat(response.getStatus()).isEqualTo(200);
  }
}
