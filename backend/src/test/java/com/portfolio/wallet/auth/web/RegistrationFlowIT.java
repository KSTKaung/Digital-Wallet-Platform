package com.portfolio.wallet.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.portfolio.wallet.WalletApplication;
import com.portfolio.wallet.auth.application.KeyedHasher;
import com.portfolio.wallet.auth.application.RegistrationService;
import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.RecordingEmailSender;
import com.portfolio.wallet.support.MutableClock;
import com.portfolio.wallet.support.TestSecrets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpServletResponse;

/** Register, verify-otp and resend-otp (documentations/08 §4A.5 cases 2 and 3). */
class RegistrationFlowIT extends AuthFlowSupport {

  private static final Duration COOLDOWN = Duration.ofSeconds(61);

  @Autowired KeyedHasher hasher;
  @Autowired Environment environment;

  private String status(String email) {
    return jdbc.queryForObject(
        "SELECT status FROM auth.users WHERE email = ?", String.class, email);
  }

  private int usersWith(String column, String value) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM auth.users WHERE " + column + " = ?", Integer.class, value);
  }

  // Register ------------------------------------------------------------------------------------

  @Test
  void registerVerifyLoginWorksEndToEndAndLoginBeforeVerificationIsTheSame401() throws Exception {
    String email = email();

    MockHttpServletResponse registered = register(email, phone());
    assertThat(registered.getStatus()).isEqualTo(201);
    assertThat(jsonMapper.readTree(registered.getContentAsString()).properties()).hasSize(2);
    assertThat(jsonMapper.readTree(registered.getContentAsString()).get("status").asString())
        .isEqualTo("PENDING_VERIFICATION");
    assertThat(status(email)).isEqualTo("PENDING_VERIFICATION");

    MockHttpServletResponse before = login(email, PASSWORD);
    MockHttpServletResponse unknown = login(email(), PASSWORD);
    assertThat(before.getStatus()).isEqualTo(401);
    assertThat(stable(before)).isEqualTo(stable(unknown));

    assertThat(verify(email, "REGISTRATION", awaitOtp(email, 1)).getStatus()).isEqualTo(200);
    assertThat(status(email)).isEqualTo("ACTIVE");
    assertThat(login(email, PASSWORD).getStatus()).isEqualTo(200);
  }

  @Test
  void theEmailIsCanonicalisedAndThePasswordStoredOnlyAsAHashInBothTables() throws Exception {
    String email = email();
    assertThat(register(email.toUpperCase(), phone()).getStatus()).isEqualTo(201);

    UUID id = jdbc.queryForObject("SELECT id FROM auth.users WHERE email = ?", UUID.class, email);
    assertThat(
            jdbc.queryForObject(
                "SELECT password_hash FROM auth.credentials WHERE user_id = ?", String.class, id))
        .startsWith("$argon2id$");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM auth.password_history WHERE user_id = ?", Integer.class, id))
        .isEqualTo(1);
  }

  @Test
  void aSubmittedRoleIsIgnored() throws Exception {
    String email = email();
    postJson(
        "register",
        Map.of("email", email, "phoneNumber", phone(), "password", PASSWORD, "role", "ADMIN"));
    assertThat(
            jdbc.queryForObject("SELECT role FROM auth.users WHERE email = ?", String.class, email))
        .isEqualTo("CUSTOMER");
  }

  @Test
  void passwordLengthBoundaries() throws Exception {
    for (int length : List.of(11, 129)) {
      MockHttpServletResponse response =
          postJson(
              "register",
              Map.of("email", email(), "phoneNumber", phone(), "password", "x".repeat(length)));
      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(response.getContentAsString()).contains("VALIDATION_ERROR", "password");
    }
    for (int length : List.of(12, 128)) {
      assertThat(
              postJson(
                      "register",
                      Map.of(
                          "email", email(), "phoneNumber", phone(), "password", "x".repeat(length)))
                  .getStatus())
          .isEqualTo(201);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"+1234567", "+1234567890123456", "0812345678", "+66-812345678"})
  void aPhoneOutsideTheE164PatternIsRefusedWithoutEchoingIt(String phone) throws Exception {
    MockHttpServletResponse response = register(email(), phone);
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("phoneNumber").doesNotContain(phone);
  }

  @Test
  void aDuplicateOfAVerifiedEmailGivesTheSameResponseAndEmailsANoticeInsteadOfACode()
      throws Exception {
    String email = email();
    MockHttpServletResponse first = register(email, phone());
    verify(email, "REGISTRATION", awaitOtp(email, 1));

    MockHttpServletResponse second = register(email, phone());

    assertThat(second.getStatus()).isEqualTo(first.getStatus());
    assertThat(stable(second)).isEqualTo(stable(first));
    awaitMailCount(email, EmailKind.EXISTING_ACCOUNT_NOTICE, 1);
    assertMailCountStays(email, EmailKind.OTP, 1);
    assertThat(usersWith("email", email)).isEqualTo(1);
  }

  @Test
  void onlyAPhoneTakenCreatesNoAccountAndEmailsThePhonesOwner() throws Exception {
    String owner = email();
    String phone = phone();
    register(owner, phone);
    String requester = email();

    MockHttpServletResponse response = register(requester, phone);

    assertThat(response.getStatus()).isEqualTo(201);
    assertThat(stable(response).replace(requester, "*"))
        .isEqualTo(stable(register(email(), phone())).replaceAll("user-[^\"]*", "*"));
    assertThat(usersWith("email", requester)).isZero();
    awaitMailCount(owner, EmailKind.EXISTING_ACCOUNT_NOTICE, 1);
    assertThat(mailTo(requester, EmailKind.EXISTING_ACCOUNT_NOTICE)).isEmpty();
    assertMailCountStays(requester, EmailKind.OTP, 0);
  }

  @Test
  void aConcurrentRaceOnOneEmailStaysA201ForEveryone() throws Exception {
    String email = email();
    List<Integer> statuses = concurrently(6, () -> register(email, phone()).getStatus());

    assertThat(statuses).containsOnly(201);
    assertThat(usersWith("email", email)).isEqualTo(1);
  }

  // Verify --------------------------------------------------------------------------------------

  @Test
  void everyVerifyFailureHasTheSameBodyAndTheAuditKeepsTheRealReason() throws Exception {
    String wrong = email();
    register(wrong, phone());
    String wrongCode = wrongCode(awaitOtp(wrong, 1));

    String expired = email();
    register(expired, phone());
    String expiredCode = awaitOtp(expired, 1);

    String consumed = email();
    register(consumed, phone());
    String consumedCode = awaitOtp(consumed, 1);
    verify(consumed, "REGISTRATION", consumedCode);

    String reference = "{\"success\":false,\"errorCode\":\"INVALID_OTP\"";
    Map<String, Map<String, String>> cases = new java.util.LinkedHashMap<>();
    cases.put("WRONG_CODE", body(wrong, "REGISTRATION", wrongCode));
    cases.put("NO_CHALLENGE", body(email(), "REGISTRATION", "123456"));
    cases.put("ALREADY_CONSUMED", body(consumed, "REGISTRATION", consumedCode));
    cases.put("UNSUPPORTED_PURPOSE", body(wrong, "LOGIN", "123456"));

    List<String> bodies = new ArrayList<>();
    for (var entry : cases.entrySet()) {
      String correlationId = UUID.randomUUID().toString();
      MockHttpServletResponse response =
          postWithCorrelation("verify-otp", entry.getValue(), correlationId);
      assertThat(response.getStatus()).isEqualTo(400);
      assertThat(response.getContentAsString()).startsWith(reference);
      bodies.add(stable(response));
      assertThat(auditReason("OTP_VERIFY", correlationId)).isEqualTo(entry.getKey());
    }
    // The other purpose's codes are not this one's: the same refusal.
    bodies.add(stable(verify(wrong, "PASSWORD_RESET", wrongCode)));
    // OTP_VERIFY rows are linked to the identifier by its keyed hash, never its address.
    String linkId = UUID.randomUUID().toString();
    postWithCorrelation("verify-otp", body(wrong, "REGISTRATION", wrongCode), linkId);
    assertThat(
            jdbc.queryForObject(
                "SELECT resource_id FROM audit.audit_logs"
                    + " WHERE action = 'OTP_VERIFY' AND correlation_id = ?",
                String.class,
                linkId))
        .isEqualTo(hasher.identifier(wrong));

    clock.advance(Duration.ofMinutes(5));
    String correlationId = UUID.randomUUID().toString();
    MockHttpServletResponse atExpiry =
        postWithCorrelation(
            "verify-otp", body(expired, "REGISTRATION", expiredCode), correlationId);
    bodies.add(stable(atExpiry));
    assertThat(auditReason("OTP_VERIFY", correlationId)).isEqualTo("EXPIRED");
    assertThat(status(expired)).isEqualTo("PENDING_VERIFICATION");

    assertThat(bodies).hasSizeGreaterThan(5).containsOnly(bodies.getFirst());
  }

  private static Map<String, String> body(String identifier, String purpose, String otp) {
    return verifyBody(identifier, purpose, otp);
  }

  @Test
  void anOtpIsValidJustBeforeItsExpiry() throws Exception {
    String email = email();
    register(email, phone());
    String code = awaitOtp(email, 1);
    clock.advance(Duration.ofMinutes(5).minusSeconds(1));
    assertThat(verify(email, "REGISTRATION", code).getStatus()).isEqualTo(200);
  }

  @Test
  void theFifthWrongGuessUsesTheChallengeUpEvenForTheRightCodeAfterwards() throws Exception {
    String email = email();
    register(email, phone());
    String code = awaitOtp(email, 1);
    for (int i = 0; i < 4; i++) {
      assertThat(verify(email, "REGISTRATION", wrongCode(code)).getStatus()).isEqualTo(400);
    }
    // Still live after four.
    String almost = email();
    register(almost, phone());
    String almostCode = awaitOtp(almost, 1);
    for (int i = 0; i < 4; i++) {
      verify(almost, "REGISTRATION", wrongCode(almostCode));
    }
    assertThat(verify(almost, "REGISTRATION", almostCode).getStatus()).isEqualTo(200);

    verify(email, "REGISTRATION", wrongCode(code));
    String correlationId = UUID.randomUUID().toString();
    assertThat(
            postWithCorrelation("verify-otp", body(email, "REGISTRATION", code), correlationId)
                .getStatus())
        .isEqualTo(400);
    assertThat(auditReason("OTP_VERIFY", correlationId)).isEqualTo("USED_UP");
    assertThat(status(email)).isEqualTo("PENDING_VERIFICATION");
  }

  @Test
  void concurrentWrongGuessesCannotExceedTheCap() throws Exception {
    String email = email();
    register(email, phone());
    String code = awaitOtp(email, 1);

    List<Integer> statuses =
        concurrently(16, () -> verify(email, "REGISTRATION", wrongCode(code)).getStatus());

    assertThat(statuses).containsOnly(400);
    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT attempts, used_up_at FROM auth.otp_challenges WHERE identifier_hash = ?",
            hasher.identifier(email));
    assertThat(row.get("attempts")).isEqualTo(5);
    assertThat(row.get("used_up_at")).isNotNull();
    assertThat(verify(email, "REGISTRATION", code).getStatus()).isEqualTo(400);
  }

  // Resend --------------------------------------------------------------------------------------

  @Test
  void resendIsAlways202WithTheSameBodyAndNothingGoesToAnUnknownIdentifier() throws Exception {
    String known = email();
    register(known, phone());
    awaitOtp(known, 1);
    String unknown = email();

    MockHttpServletResponse inCooldown = resend(known);
    MockHttpServletResponse forUnknown = resend(unknown);

    assertThat(inCooldown.getStatus()).isEqualTo(202);
    assertThat(forUnknown.getStatus()).isEqualTo(202);
    assertThat(inCooldown.getContentAsString())
        .isEqualTo(forUnknown.getContentAsString())
        .isEmpty();
    assertMailCountStays(unknown, EmailKind.OTP, 0);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM auth.otp_challenges WHERE identifier_hash = ?",
                Integer.class,
                hasher.identifier(unknown)))
        .isEqualTo(1);
  }

  @Test
  void resendForAnActiveAccountOrAnotherPurposeSendsNothing() throws Exception {
    String email = activeUser();
    clock.advance(COOLDOWN);
    assertThat(resend(email).getStatus()).isEqualTo(202);
    assertThat(postJson("resend-otp", Map.of("identifier", email, "purpose", "LOGIN")).getStatus())
        .isEqualTo(202);
    assertMailCountStays(email, EmailKind.OTP, 1);
  }

  @Test
  void theCooldownSilentlySendsNothingAndAResendCancelsThePreviousCode() throws Exception {
    String email = email();
    register(email, phone());
    String first = awaitOtp(email, 1);

    clock.advance(Duration.ofSeconds(59));
    assertThat(resend(email).getStatus()).isEqualTo(202);
    assertMailCountStays(email, EmailKind.OTP, 1);

    clock.advance(Duration.ofSeconds(1));
    assertThat(resend(email).getStatus()).isEqualTo(202);
    String second = awaitOtp(email, 2);

    if (!first.equals(second)) {
      String correlationId = UUID.randomUUID().toString();
      assertThat(
              postWithCorrelation("verify-otp", body(email, "REGISTRATION", first), correlationId)
                  .getStatus())
          .isEqualTo(400);
      assertThat(auditReason("OTP_VERIFY", correlationId)).isEqualTo("WRONG_CODE");
    }
    assertThat(verify(email, "REGISTRATION", second).getStatus()).isEqualTo(200);
  }

  @Test
  void atMostFiveResendsPerHour() throws Exception {
    String email = email();
    register(email, phone());
    awaitOtp(email, 1);
    for (int i = 0; i < 5; i++) {
      clock.advance(COOLDOWN);
      resend(email);
      awaitOtp(email, i + 2);
    }

    clock.advance(COOLDOWN);
    assertThat(resend(email).getStatus()).isEqualTo(202);
    assertMailCountStays(email, EmailKind.OTP, 6);

    clock.advance(Duration.ofHours(1));
    resend(email);
    awaitOtp(email, 7);
  }

  private void spendChallenge(String email, String code) throws Exception {
    for (int i = 0; i < 5; i++) {
      verify(email, "REGISTRATION", wrongCode(code));
    }
  }

  @Test
  void fiveUsedUpChallengesBlockNewOnesUntilTheWindowClears() throws Exception {
    String email = email();
    register(email, phone());
    spendChallenge(email, awaitOtp(email, 1));
    for (int count = 2; count <= 5; count++) {
      clock.advance(COOLDOWN);
      resend(email);
      spendChallenge(email, awaitOtp(email, count));
    }

    clock.advance(COOLDOWN);
    assertThat(resend(email).getStatus()).isEqualTo(202);
    assertMailCountStays(email, EmailKind.OTP, 5);

    // One full window later all five have left it.
    clock.advance(Duration.ofHours(24));
    resend(email);
    awaitOtp(email, 6);
  }

  @Test
  void theLimitsSurviveANewApplicationContext() throws Exception {
    String capped = email();
    register(capped, phone());
    awaitOtp(capped, 1);
    for (int i = 0; i < 5; i++) {
      clock.advance(COOLDOWN);
      resend(capped);
      awaitOtp(capped, i + 2);
    }
    String cooling = email();
    register(cooling, phone());
    awaitOtp(cooling, 1);
    String guessed = email();
    register(guessed, phone());
    String guessedCode = awaitOtp(guessed, 1);
    for (int i = 0; i < 4; i++) {
      verify(guessed, "REGISTRATION", wrongCode(guessedCode));
    }

    try (ConfigurableApplicationContext restarted = restartedContext()) {
      restarted.getBean(MutableClock.class).set(clock.instant());
      RegistrationService service = restarted.getBean(RegistrationService.class);

      service.resend(cooling, "REGISTRATION", null);

      assertThatThrownByVerify(service, guessed, wrongCode(guessedCode));
      assertThat(verify(guessed, "REGISTRATION", guessedCode).getStatus()).isEqualTo(400);

      restarted.getBean(MutableClock.class).advance(COOLDOWN);
      service.resend(capped, "REGISTRATION", null);

      // A code is mailed only when a challenge is created, and these commit before resend returns.
      assertThat(challengeCount(capped)).isEqualTo(6);
      assertThat(issueRefusals(capped, "HOURLY_CAP")).isEqualTo(1);
      assertThat(challengeCount(cooling)).isEqualTo(1);
      assertThat(issueRefusals(cooling, "COOLDOWN")).isEqualTo(1);
    }
  }

  private int challengeCount(String email) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM auth.otp_challenges WHERE identifier_hash = ?",
        Integer.class,
        hasher.identifier(email));
  }

  private int issueRefusals(String email, String reason) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit.audit_logs WHERE action = 'OTP_ISSUE'"
            + " AND resource_id = ? AND result = 'DENIED' AND new_value ->> 'reason' = ?",
        Integer.class,
        hasher.identifier(email),
        reason);
  }

  // The total-guesses block -------------------------------------------------------------------

  private void guess(String email, String code, int times) throws Exception {
    for (int i = 0; i < times; i++) {
      assertThat(verify(email, "REGISTRATION", wrongCode(code)).getStatus()).isEqualTo(400);
    }
  }

  /** Six challenges of four guesses each, then one more guess: 25 in all, and none used up. */
  private String twentyFiveGuessesWithNoChallengeUsedUp(Duration between) throws Exception {
    String email = email();
    register(email, phone());
    for (int challenge = 1; challenge <= 6; challenge++) {
      guess(email, awaitOtp(email, challenge), 4);
      // Halfway, a gap that clears the hourly cap on resends.
      clock.advance(challenge == 3 ? between.plusHours(1) : between);
      resend(email);
    }
    guess(email, awaitOtp(email, 7), 1);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM auth.otp_challenges"
                    + " WHERE identifier_hash = ? AND used_up_at IS NOT NULL",
                Integer.class,
                hasher.identifier(email)))
        .isZero();
    return email;
  }

  // Short enough for the code to be live when the next resend cancels it.
  private static final Duration STILL_LIVE = Duration.ofMinutes(4).plusSeconds(50);
  // The code has long expired by the next resend, and this stays under the hourly cap.
  private static final Duration EXPIRED_AND_UNCAPPED = Duration.ofMinutes(13);

  private void assertBlockedUntilTheWindowClears(String email) throws Exception {
    clock.advance(EXPIRED_AND_UNCAPPED);
    resend(email);
    assertThat(challengeCount(email)).isEqualTo(7);
    assertThat(issueRefusals(email, "USED_UP_BLOCK")).isEqualTo(1);

    clock.advance(Duration.ofHours(24));
    resend(email);
    assertThat(challengeCount(email)).isEqualTo(8);
    awaitOtp(email, 8);
  }

  @Test
  void cancellingEachChallengeBeforeItsLastGuessDoesNotEscapeTheBlock() throws Exception {
    String email = twentyFiveGuessesWithNoChallengeUsedUp(STILL_LIVE);
    assertBlockedUntilTheWindowClears(email);
  }

  @Test
  void lettingEachChallengeExpireDoesNotEscapeTheBlock() throws Exception {
    String email = twentyFiveGuessesWithNoChallengeUsedUp(EXPIRED_AND_UNCAPPED);
    assertBlockedUntilTheWindowClears(email);
  }

  @Test
  void twentyFourGuessesDoNotBlock() throws Exception {
    String email = email();
    register(email, phone());
    for (int challenge = 1; challenge <= 6; challenge++) {
      guess(email, awaitOtp(email, challenge), 4);
      clock.advance(EXPIRED_AND_UNCAPPED);
      resend(email);
    }
    assertThat(challengeCount(email)).isEqualTo(7);
    assertThat(issueRefusals(email, "USED_UP_BLOCK")).isZero();
  }

  @Test
  void guessesCountAcrossPurposes() throws Exception {
    String email = email();
    register(email, phone());
    for (int challenge = 1; challenge <= 5; challenge++) {
      guess(email, awaitOtp(email, challenge), 4);
      clock.advance(EXPIRED_AND_UNCAPPED);
      resend(email);
    }
    postJson("forgot-password", Map.of("email", email));
    for (int i = 0; i < 5; i++) {
      verify(email, "PASSWORD_RESET", "000000");
    }
    clock.advance(EXPIRED_AND_UNCAPPED);
    resend(email);
    assertThat(issueRefusals(email, "USED_UP_BLOCK")).isEqualTo(1);
  }

  // Non-ASCII addresses --------------------------------------------------------------------------

  /** The address format check stops at the BMP, so this is a plain validation error. */
  @Test
  void anAddressOutsideTheBmpIsAValidationErrorNotA500() throws Exception {
    MockHttpServletResponse response = register("𐐀-" + randomDigits(6) + "@example.test", phone());
    assertThat(response.getStatus()).isEqualTo(400);
    assertThat(response.getContentAsString()).contains("VALIDATION_ERROR", "email:");
  }

  /**
   * Java lower-cases the address; {@code ck_users_email} requires {@code lower(email)} to change
   * nothing under the database's locale. These letters change length or are recent additions to
   * Unicode, which is where the two could disagree.
   */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "ÉLODIE", // É
        "İSTANBUL", // İ, which lower-cases to two code points in Java
        "STRAẞE", // capital ẞ
        "ΣΣ", // Σ, with its final form
        "ΩHM", // the ohm sign
        "ᲐᲑ", // Georgian Mtavruli, Unicode 11
        "ႠႡ", // Georgian Asomtavruli
      })
  void aNonAsciiEmailRegistersVerifiesAndLogsIn(String local) throws Exception {
    String address = local + "-" + randomDigits(6) + "@EXAMPLE.test";

    MockHttpServletResponse registered = register(address, phone());
    assertThat(registered.getStatus()).isEqualTo(201);

    String canonical = jsonMapper.readTree(registered.getContentAsString()).get("email").asString();
    assertThat(usersWith("email", canonical)).isEqualTo(1);
    assertThat(verify(address, "REGISTRATION", awaitOtp(canonical, 1)).getStatus()).isEqualTo(200);
    assertThat(login(address, PASSWORD).getStatus()).isEqualTo(200);
  }

  @Test
  void theUsedUpBlockSurvivesANewApplicationContextUntilTheWindowClears() throws Exception {
    String email = email();
    register(email, phone());
    spendChallenge(email, awaitOtp(email, 1));
    for (int count = 2; count <= 5; count++) {
      clock.advance(COOLDOWN);
      resend(email);
      spendChallenge(email, awaitOtp(email, count));
    }
    String identifier = hasher.identifier(email);
    String challenges = "SELECT count(*) FROM auth.otp_challenges WHERE identifier_hash = ?";
    assertThat(jdbc.queryForObject(challenges, Integer.class, identifier)).isEqualTo(5);

    try (ConfigurableApplicationContext restarted = restartedContext()) {
      MutableClock restartedClock = restarted.getBean(MutableClock.class);
      restartedClock.set(clock.instant().plus(COOLDOWN));
      RegistrationService service = restarted.getBean(RegistrationService.class);
      RecordingEmailSender restartedMail = restarted.getBean(RecordingEmailSender.class);

      service.resend(email, "REGISTRATION", null);

      // The refusal commits before resend returns, and only the block can explain it: this is past
      // the cooldown, and four resends are under the hourly cap.
      assertThat(jdbc.queryForObject(challenges, Integer.class, identifier)).isEqualTo(5);
      assertThat(
              jdbc.queryForObject(
                  "SELECT count(*) FROM audit.audit_logs WHERE action = 'OTP_ISSUE'"
                      + " AND resource_id = ? AND new_value ->> 'reason' = 'USED_UP_BLOCK'",
                  Integer.class,
                  identifier))
          .isEqualTo(1);

      // The same context does send once the window clears, so the silence above was the block. The
      // one message also shows the blocked request queued none.
      restartedClock.advance(Duration.ofHours(24));
      service.resend(email, "REGISTRATION", null);
      await().atMost(Duration.ofSeconds(10)).until(() -> !restartedMail.messages().isEmpty());
      assertThat(restartedMail.messages()).hasSize(1);
      assertThat(jdbc.queryForObject(challenges, Integer.class, identifier)).isEqualTo(6);
    }
  }

  @Test
  void aMalformedResendBodyIs400AndSaysNothingAboutAnyAccount() throws Exception {
    String known = email();
    register(known, phone());
    awaitOtp(known, 1);

    MockHttpServletResponse forKnown =
        postJson("resend-otp", Map.of("identifier", known, "purpose", "NOT_A_PURPOSE"));
    MockHttpServletResponse forUnknown =
        postJson("resend-otp", Map.of("identifier", email(), "purpose", "NOT_A_PURPOSE"));
    MockHttpServletResponse noIdentifier =
        postJson("resend-otp", Map.of("purpose", "REGISTRATION"));

    assertThat(forKnown.getStatus()).isEqualTo(400);
    assertThat(forKnown.getContentAsString())
        .contains("\"errorCode\":\"VALIDATION_ERROR\"", "purpose")
        .doesNotContain(known, "NOT_A_PURPOSE");
    assertThat(stable(forKnown)).isEqualTo(stable(forUnknown));
    assertThat(noIdentifier.getStatus()).isEqualTo(400);
    assertThat(noIdentifier.getContentAsString()).contains("VALIDATION_ERROR", "identifier");
    assertMailCountStays(known, EmailKind.OTP, 1);
  }

  private static void assertThatThrownByVerify(
      RegistrationService service, String email, String code) {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> service.verify(email, "REGISTRATION", code, PASSWORD, null))
        .hasMessageContaining("Invalid");
  }

  private ConfigurableApplicationContext restartedContext() {
    List<String> args = new ArrayList<>(List.of("--server.port=0"));
    for (String name :
        List.of(
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.datasource.password",
            "spring.flyway.user",
            "spring.flyway.password")) {
      args.add("--" + name + "=" + environment.getProperty(name));
    }
    TestSecrets.values().forEach((name, value) -> args.add("--" + name + "=" + value));
    return new SpringApplicationBuilder(WalletApplication.class, Beans.class)
        .run(args.toArray(String[]::new));
  }

  private static <T> List<T> concurrently(int threads, Callable<T> task) throws Exception {
    try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
      List<Future<T>> futures = new ArrayList<>();
      for (int i = 0; i < threads; i++) {
        futures.add(pool.submit(task));
      }
      List<T> results = new ArrayList<>();
      for (Future<T> future : futures) {
        results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      }
      return results;
    }
  }
}
