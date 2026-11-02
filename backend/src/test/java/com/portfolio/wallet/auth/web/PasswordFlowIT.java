package com.portfolio.wallet.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.portfolio.wallet.auth.application.AuthProperties;
import com.portfolio.wallet.auth.application.KeyedHasher;
import com.portfolio.wallet.notification.application.EmailKind;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

/** Forgot, verify, reset and change password (documentations/08 §4A.5; AC 3, 4, 5, 8). */
class PasswordFlowIT extends AuthFlowSupport {

  private static final String NEW_PASSWORD = "a-brand-new-password";
  private static final Duration COOLDOWN = Duration.ofSeconds(60);
  private static final Duration RESET_TTL = Duration.ofMinutes(15);

  @Autowired AuthProperties properties;
  @Autowired KeyedHasher hasher;

  private MockHttpServletResponse forgot(String email) throws Exception {
    return postJson("forgot-password", Map.of("email", email));
  }

  private MockHttpServletResponse reset(String token, String password) throws Exception {
    return postJson("reset-password", Map.of("resetToken", token, "newPassword", password));
  }

  private MockHttpServletResponse change(String accessToken, String current, String next)
      throws Exception {
    return mvc.perform(
            post(API + "change-password")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    jsonMapper.writeValueAsString(
                        Map.of("currentPassword", current, "newPassword", next))))
        .andReturn()
        .getResponse();
  }

  /** The token for the {@code otpMail}-th OTP email to {@code email}. */
  private String resetTokenFor(String email, int otpMail) throws Exception {
    assertThat(forgot(email).getStatus()).isEqualTo(202);
    MockHttpServletResponse verified = verify(email, "PASSWORD_RESET", awaitOtp(email, otpMail));
    assertThat(verified.getStatus()).isEqualTo(200);
    return jsonMapper.readTree(verified.getContentAsString()).get("resetToken").asString();
  }

  private void setStatus(String email, String status) {
    jdbc.update("UPDATE auth.users SET status = ? WHERE email = ?", status, email);
  }

  private String passwordHash(String email) {
    return jdbc.queryForObject(
        "SELECT c.password_hash FROM auth.credentials c JOIN auth.users u ON u.id = c.user_id"
            + " WHERE u.email = ?",
        String.class,
        email);
  }

  private int challenges(String email) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM auth.otp_challenges WHERE identifier_hash = ? AND purpose = ?",
        Integer.class,
        hasher.identifier(email),
        "PASSWORD_RESET");
  }

  private static void assertInvalidToken(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString())
        .contains("\"errorCode\":\"VALIDATION_ERROR\"")
        .contains("Invalid or expired reset token");
  }

  private static void assertPolicyRefusal(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString())
        .contains("\"errorCode\":\"VALIDATION_ERROR\"")
        .contains("newPassword");
  }

  // Forgot ---------------------------------------------------------------------------------------

  @Test
  void theFullFlowResetsThePasswordAndEndsEverySession() throws Exception {
    String email = activeUser();
    Tokens session = loginTokens(email);

    String token = resetTokenFor(email, 2);
    assertThat(token.length()).isGreaterThanOrEqualTo(43);
    assertThat(reset(token, NEW_PASSWORD).getStatus()).isEqualTo(200);

    assertThat(probe(session.accessToken()).getStatus()).isEqualTo(401);
    assertThat(refresh(session.refreshToken()).getStatus()).isEqualTo(401);
    assertThat(login(email, PASSWORD).getStatus()).isEqualTo(401);
    assertThat(login(email, NEW_PASSWORD).getStatus()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(DISTINCT revoked_reason) FROM auth.sessions s"
                    + " JOIN auth.users u ON u.id = s.user_id"
                    + " WHERE u.email = ? AND revoked_reason = 'PASSWORD_RESET'",
                Integer.class,
                email))
        .isEqualTo(1);
    assertInvalidToken(reset(token, "yet-another-password"));
  }

  @Test
  void forgotIsTheSameForEveryAccountStateAndOnlyAnActiveAccountGetsACode() throws Exception {
    String active = activeUser();
    String pending = email();
    register(pending, phone());
    awaitOtp(pending, 1);
    String frozen = activeUser();
    setStatus(frozen, "FROZEN");
    String suspended = activeUser();
    setStatus(suspended, "SUSPENDED");
    String unknown = email();

    List<String> bodies = new ArrayList<>();
    for (String email : List.of(active, pending, frozen, suspended, unknown)) {
      MockHttpServletResponse response = forgot(email);
      assertThat(response.getStatus()).isEqualTo(202);
      bodies.add(response.getContentAsString());
      assertThat(challenges(email)).isEqualTo(1);
    }

    assertThat(bodies).containsOnly("");
    awaitMailCount(active, EmailKind.OTP, 2);
    assertMailCountStays(pending, EmailKind.OTP, 1);
    assertMailCountStays(frozen, EmailKind.OTP, 1);
    assertMailCountStays(suspended, EmailKind.OTP, 1);
    assertMailCountStays(unknown, EmailKind.OTP, 0);
  }

  @Test
  void theCooldownSilentlySendsNothing() throws Exception {
    String email = activeUser();
    forgot(email);
    awaitOtp(email, 2);
    assertThat(forgot(email).getStatus()).isEqualTo(202);
    assertMailCountStays(email, EmailKind.OTP, 2);
  }

  @Test
  void resendWithThisPurposeCancelsThePreviousCode() throws Exception {
    String email = activeUser();
    forgot(email);
    String first = awaitOtp(email, 2);
    clock.advance(COOLDOWN);
    assertThat(
            postJson("resend-otp", Map.of("identifier", email, "purpose", "PASSWORD_RESET"))
                .getStatus())
        .isEqualTo(202);
    String second = awaitOtp(email, 3);

    assertThat(verify(email, "PASSWORD_RESET", first).getStatus()).isEqualTo(400);
    assertThat(verify(email, "PASSWORD_RESET", second).getStatus()).isEqualTo(200);
  }

  // Verify ---------------------------------------------------------------------------------------

  @Test
  void aWrongCodeIsTheSameInvalidOtpWhateverTheAccount() throws Exception {
    String known = activeUser();
    forgot(known);
    String code = awaitOtp(known, 2);

    MockHttpServletResponse wrong = verify(known, "PASSWORD_RESET", wrongCode(code));
    MockHttpServletResponse unknown = verify(email(), "PASSWORD_RESET", "123456");

    assertThat(wrong.getStatus()).isEqualTo(400);
    assertThat(wrong.getContentAsString()).contains("\"errorCode\":\"INVALID_OTP\"");
    assertThat(stable(wrong)).isEqualTo(stable(unknown));
  }

  @Test
  void aRightCodeForAnAccountFrozenSinceIsInvalidOtpAndGrantsNoToken() throws Exception {
    String email = activeUser();
    forgot(email);
    String code = awaitOtp(email, 2);
    setStatus(email, "FROZEN");

    MockHttpServletResponse response = verify(email, "PASSWORD_RESET", code);

    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("\"errorCode\":\"INVALID_OTP\"");
  }

  @Test
  void aNewTokenInvalidatesTheEarlierOne() throws Exception {
    String email = activeUser();
    String first = resetTokenFor(email, 2);
    clock.advance(COOLDOWN);
    String second = resetTokenFor(email, 3);

    assertInvalidToken(reset(first, NEW_PASSWORD));
    assertThat(reset(second, NEW_PASSWORD).getStatus()).isEqualTo(200);
  }

  // Reset ----------------------------------------------------------------------------------------

  @Test
  void anUnknownTokenIsRefusedGenerically() throws Exception {
    assertInvalidToken(reset("no-such-token", NEW_PASSWORD));
  }

  @Test
  void aTokenWorksJustBeforeItsExpiryAndNotAtIt() throws Exception {
    String working = activeUser();
    String justInTime = resetTokenFor(working, 2);
    clock.advance(RESET_TTL.minusSeconds(1));
    assertThat(reset(justInTime, NEW_PASSWORD).getStatus()).isEqualTo(200);

    String late = activeUser();
    String expired = resetTokenFor(late, 2);
    clock.advance(RESET_TTL);
    assertInvalidToken(reset(expired, NEW_PASSWORD));
    assertThat(login(late, PASSWORD).getStatus()).isEqualTo(200);
  }

  @Test
  void aPolicyFailureNamesTheFieldAndDoesNotConsumeTheToken() throws Exception {
    String email = activeUser();
    String token = resetTokenFor(email, 2);

    assertPolicyRefusal(reset(token, "x".repeat(properties.password().minLength() - 1)));
    assertPolicyRefusal(reset(token, "x".repeat(properties.password().maxLength() + 1)));
    assertPolicyRefusal(reset(token, PASSWORD));

    assertThat(reset(token, NEW_PASSWORD).getStatus()).isEqualTo(200);
  }

  @Test
  void aTokenIssuedBeforeAFreezeIsRefusedAndChangesNothing() throws Exception {
    String email = activeUser();
    String token = resetTokenFor(email, 2);
    String before = passwordHash(email);
    setStatus(email, "FROZEN");

    assertInvalidToken(reset(token, NEW_PASSWORD));

    assertThat(passwordHash(email)).isEqualTo(before);
    assertThat(login(email, PASSWORD).getStatus()).isEqualTo(401);
  }

  @Test
  void concurrentUsesOfOneTokenHaveExactlyOneWinner() throws Exception {
    String email = activeUser();
    String token = resetTokenFor(email, 2);

    List<Integer> statuses = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
      List<Future<Integer>> futures = new ArrayList<>();
      for (int i = 0; i < 6; i++) {
        String password = NEW_PASSWORD + "-" + i;
        futures.add(pool.submit(() -> reset(token, password).getStatus()));
      }
      for (Future<Integer> future : futures) {
        statuses.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      }
    }

    assertThat(statuses).containsOnly(200, 400);
    assertThat(statuses.stream().filter(status -> status == 200)).hasSize(1);
  }

  @Test
  void aResetClearsTheLockAndTheOwnerOfALockedAccountGetsTheCode() throws Exception {
    String email = activeUser();
    for (int i = 0; i < properties.lockout().maxFailedAttempts(); i++) {
      login(email, "a-wrong-password-here");
    }
    assertThat(login(email, PASSWORD).getStatus()).isEqualTo(401);

    assertThat(reset(resetTokenFor(email, 2), NEW_PASSWORD).getStatus()).isEqualTo(200);

    assertThat(login(email, NEW_PASSWORD).getStatus()).isEqualTo(200);
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT c.failed_login_count, c.locked_until FROM auth.credentials c"
                + " JOIN auth.users u ON u.id = c.user_id WHERE u.email = ?",
            email);
    assertThat(row.get("failed_login_count")).isEqualTo(0);
    assertThat(row.get("locked_until")).isNull();
  }

  @Test
  void aResetClearsACounterLeftFromBeforeTheAccountExisted() throws Exception {
    String email = email();
    login(email, "a-wrong-password-here");
    String counters = "SELECT count(*) FROM auth.login_failure_counters WHERE identifier_hash = ?";
    assertThat(jdbc.queryForObject(counters, Integer.class, hasher.identifier(email))).isEqualTo(1);
    register(email, phone());
    verify(email, "REGISTRATION", awaitOtp(email, 1));

    assertThat(reset(resetTokenFor(email, 2), NEW_PASSWORD).getStatus()).isEqualTo(200);

    assertThat(jdbc.queryForObject(counters, Integer.class, hasher.identifier(email))).isZero();
  }

  // History --------------------------------------------------------------------------------------

  @Test
  void eachOfTheLastFivePasswordsIsRefusedAndTheSixthOldestIsAccepted() throws Exception {
    String email = activeUser();
    List<String> used = List.of("history-password-1", "history-password-2", "history-password-3");
    List<String> lastFive = new ArrayList<>(used);
    lastFive.add("history-password-4");
    lastFive.add("history-password-5");
    String current = PASSWORD;
    for (String next : lastFive) {
      String token =
          jsonMapper
              .readTree(login(email, current).getContentAsString())
              .get("accessToken")
              .asString();
      assertThat(change(token, current, next).getStatus()).isEqualTo(200);
      current = next;
    }

    String token =
        jsonMapper
            .readTree(login(email, current).getContentAsString())
            .get("accessToken")
            .asString();
    for (String recent : lastFive) {
      assertPolicyRefusal(change(token, current, recent));
    }
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM auth.password_history h JOIN auth.users u"
                    + " ON u.id = h.user_id WHERE u.email = ?",
                Integer.class,
                email))
        .isEqualTo(properties.password().historyDepth());
    assertThat(change(token, current, PASSWORD).getStatus()).isEqualTo(200);
    assertThat(login(email, PASSWORD).getStatus()).isEqualTo(200);
  }

  private static final List<String> LAST_FIVE =
      List.of(
          "history-password-1",
          "history-password-2",
          "history-password-3",
          "history-password-4",
          "history-password-5");

  private String accessToken(String email, String password) throws Exception {
    return jsonMapper
        .readTree(login(email, password).getContentAsString())
        .get("accessToken")
        .asString();
  }

  @Test
  void eachOfTheLastFiveIsRefusedOnTheResetPathAndTheSixthOldestIsAccepted() throws Exception {
    String email = activeUser();
    String current = PASSWORD;
    for (String next : LAST_FIVE) {
      assertThat(change(accessToken(email, current), current, next).getStatus()).isEqualTo(200);
      current = next;
    }
    String token = resetTokenFor(email, 2);
    String before = passwordHash(email);

    for (String recent : LAST_FIVE) {
      assertPolicyRefusal(reset(token, recent));
      assertThat(passwordHash(email)).isEqualTo(before);
    }

    assertThat(reset(token, PASSWORD).getStatus()).isEqualTo(200);
    assertThat(login(email, PASSWORD).getStatus()).isEqualTo(200);
  }

  @Test
  void passwordsSetByResetCountAsHistoryOnTheChangePath() throws Exception {
    String email = activeUser();
    int otpMail = 2;
    for (String next : LAST_FIVE) {
      assertThat(reset(resetTokenFor(email, otpMail), next).getStatus()).isEqualTo(200);
      otpMail++;
      clock.advance(COOLDOWN);
    }
    String current = LAST_FIVE.getLast();
    String access = accessToken(email, current);
    String before = passwordHash(email);

    for (String recent : LAST_FIVE) {
      assertPolicyRefusal(change(access, current, recent));
      assertThat(passwordHash(email)).isEqualTo(before);
    }

    assertThat(change(access, current, PASSWORD).getStatus()).isEqualTo(200);
    assertThat(login(email, PASSWORD).getStatus()).isEqualTo(200);
  }

  @Test
  void aResetCodeWorksOnlyOnce() throws Exception {
    String email = activeUser();
    forgot(email);
    String code = awaitOtp(email, 2);
    assertThat(verify(email, "PASSWORD_RESET", code).getStatus()).isEqualTo(200);

    MockHttpServletResponse again = verify(email, "PASSWORD_RESET", code);

    assertThat(again.getStatus()).isEqualTo(400);
    assertThat(again.getContentAsString()).contains("\"errorCode\":\"INVALID_OTP\"");
  }

  // Change ---------------------------------------------------------------------------------------

  @Test
  void aChangeRefusesTheCurrentTokenAtOnceAndEndsEverySession() throws Exception {
    String email = activeUser();
    Tokens other = loginTokens(email);
    Tokens current = loginTokens(email);

    MockHttpServletResponse changed = change(current.accessToken(), PASSWORD, NEW_PASSWORD);

    assertThat(changed.getStatus()).isEqualTo(200);
    assertThat(changed.getHeader(HttpHeaders.SET_COOKIE)).contains("Max-Age=0");
    assertThat(probe(current.accessToken()).getStatus()).isEqualTo(401);
    assertThat(probe(other.accessToken()).getStatus()).isEqualTo(401);
    assertThat(refresh(current.refreshToken()).getStatus()).isEqualTo(401);
    assertThat(refresh(other.refreshToken()).getStatus()).isEqualTo(401);
    assertThat(login(email, PASSWORD).getStatus()).isEqualTo(401);
    assertThat(login(email, NEW_PASSWORD).getStatus()).isEqualTo(200);
  }

  @Test
  void aChangeEndsAnEarlierResetTokenAndALiveResetCode() throws Exception {
    String email = activeUser();
    String token = resetTokenFor(email, 2);
    clock.advance(COOLDOWN);
    assertThat(forgot(email).getStatus()).isEqualTo(202);
    String code = awaitOtp(email, 3);
    Tokens tokens = loginTokens(email);

    assertThat(change(tokens.accessToken(), PASSWORD, NEW_PASSWORD).getStatus()).isEqualTo(200);

    assertInvalidToken(reset(token, "yet-another-password"));
    String correlationId = UUID.randomUUID().toString();
    assertThat(
            postWithCorrelation(
                    "verify-otp", verifyBody(email, "PASSWORD_RESET", code), correlationId)
                .getStatus())
        .isEqualTo(400);
    assertThat(auditReason("OTP_VERIFY", correlationId)).isEqualTo("CANCELLED");
    assertThat(login(email, NEW_PASSWORD).getStatus()).isEqualTo(200);
  }

  @Test
  void aRefusedRequestForAnEligibleAccountIsNotAuditedAsASuccess() throws Exception {
    String email = activeUser();
    String first = UUID.randomUUID().toString();
    String second = UUID.randomUUID().toString();
    postWithCorrelation("forgot-password", Map.of("email", email), first);
    postWithCorrelation("forgot-password", Map.of("email", email), second);

    String query =
        "SELECT result FROM audit.audit_logs"
            + " WHERE action = 'PASSWORD_RESET_REQUEST' AND correlation_id = ?";
    assertThat(jdbc.queryForObject(query, String.class, first)).isEqualTo("SUCCESS");
    assertThat(jdbc.queryForObject(query, String.class, second)).isEqualTo("DENIED");
    assertThat(auditReason("PASSWORD_RESET_REQUEST", second)).isEqualTo("COOLDOWN");
  }

  @Test
  void aWrongCurrentPasswordIsTheGeneric401AndDoesNotCountTowardsTheLockout() throws Exception {
    String email = activeUser();
    Tokens tokens = loginTokens(email);
    MockHttpServletResponse anonymous =
        mvc.perform(post(API + "change-password")).andReturn().getResponse();

    for (int i = 0; i <= properties.lockout().maxFailedAttempts(); i++) {
      MockHttpServletResponse wrong =
          change(tokens.accessToken(), "not-the-password", NEW_PASSWORD);
      assertThat(wrong.getStatus()).isEqualTo(401);
      assertThat(stable(wrong)).isEqualTo(stable(anonymous));
    }

    assertThat(probe(tokens.accessToken()).getStatus()).isEqualTo(200);
    assertThat(login(email, PASSWORD).getStatus()).isEqualTo(200);
  }

  @Test
  void aPolicyFailureOnChangeLeavesThePasswordAndTheSession() throws Exception {
    String email = activeUser();
    Tokens tokens = loginTokens(email);

    assertPolicyRefusal(change(tokens.accessToken(), PASSWORD, "short"));

    assertThat(probe(tokens.accessToken()).getStatus()).isEqualTo(200);
    assertThat(login(email, PASSWORD).getStatus()).isEqualTo(200);
  }
}
