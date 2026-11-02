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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * An attacker registers someone else's address and waits for its owner to click through. Whoever
 * controls the mailbox wins an account nobody has verified, and the password that activates it is
 * the one proven alongside the code.
 */
class PendingAccountIT extends AuthFlowSupport {

  private static final String ATTACKER_PASSWORD = "the-attackers-password";
  private static final String VICTIM_PASSWORD = "the-victims-own-password";
  private static final Duration COOLDOWN = Duration.ofSeconds(61);

  @Autowired KeyedHasher hasher;

  private String phoneOf(String email) {
    return jdbc.queryForObject(
        "SELECT phone_number FROM auth.users WHERE email = ?", String.class, email);
  }

  private String statusOf(String email) {
    return jdbc.queryForObject(
        "SELECT status FROM auth.users WHERE email = ?", String.class, email);
  }

  private int usersWithPhone(String phone) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM auth.users WHERE phone_number = ?", Integer.class, phone);
  }

  private String hashOf(String email) {
    return jdbc.queryForObject(
        "SELECT c.password_hash FROM auth.credentials c JOIN auth.users u ON u.id = c.user_id"
            + " WHERE u.email = ?",
        String.class,
        email);
  }

  private List<String> historyOf(String email) {
    return jdbc.queryForList(
        "SELECT h.password_hash FROM auth.password_history h JOIN auth.users u ON u.id = h.user_id"
            + " WHERE u.email = ?",
        String.class,
        email);
  }

  private int attempts(String email) {
    return jdbc.queryForObject(
        "SELECT attempts FROM auth.otp_challenges WHERE identifier_hash = ?"
            + " AND consumed_at IS NULL AND cancelled_at IS NULL",
        Integer.class,
        hasher.identifier(email));
  }

  private int liveChallenges(String email) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM auth.otp_challenges WHERE identifier_hash = ?"
            + " AND consumed_at IS NULL AND cancelled_at IS NULL AND used_up_at IS NULL",
        Integer.class,
        hasher.identifier(email));
  }

  // The attack ------------------------------------------------------------------------------------

  @Test
  void theFullAttackSequenceEndsWithTheMailboxOwnerInControl() throws Exception {
    String email = email();
    String attackerPhone = phone();
    String victimPhone = phone();

    MockHttpServletResponse byAttacker = registerWith(email, attackerPhone, ATTACKER_PASSWORD);
    awaitOtp(email, 1);
    clock.advance(COOLDOWN);
    MockHttpServletResponse byVictim = registerWith(email, victimPhone, VICTIM_PASSWORD);

    assertThat(byVictim.getStatus()).isEqualTo(201);
    assertThat(stable(byVictim)).isEqualTo(stable(byAttacker));
    String code = awaitOtp(email, 2);
    assertThat(mailTo(email, EmailKind.EXISTING_ACCOUNT_NOTICE)).isEmpty();

    assertThat(verifyWith(email, code, ATTACKER_PASSWORD).getStatus()).isEqualTo(400);
    assertThat(statusOf(email)).isEqualTo("PENDING_VERIFICATION");
    assertThat(verifyWith(email, code, VICTIM_PASSWORD).getStatus()).isEqualTo(200);

    assertThat(statusOf(email)).isEqualTo("ACTIVE");
    assertThat(login(email, ATTACKER_PASSWORD).getStatus()).isEqualTo(401);
    assertThat(login(email, VICTIM_PASSWORD).getStatus()).isEqualTo(200);
    assertThat(phoneOf(email)).isEqualTo(victimPhone);
    assertThat(usersWithPhone(attackerPhone)).isZero();
    assertThat(historyOf(email)).containsExactly(hashOf(email));
  }

  @Test
  void anAttackerReRegisteringAfterTheVictimCannotActivate() throws Exception {
    String email = email();
    registerWith(email, phone(), ATTACKER_PASSWORD);
    awaitOtp(email, 1);
    clock.advance(COOLDOWN);
    registerWith(email, phone(), VICTIM_PASSWORD);
    String victimsCode = awaitOtp(email, 2);
    clock.advance(COOLDOWN);

    registerWith(email, phone(), ATTACKER_PASSWORD);

    // The attacker has no mailbox: the code they know of is dead, with any password.
    String newCode = awaitOtp(email, 3);
    String stale = victimsCode.equals(newCode) ? wrongCode(newCode) : victimsCode;
    assertThat(verifyWith(email, stale, ATTACKER_PASSWORD).getStatus()).isEqualTo(400);
    assertThat(statusOf(email)).isEqualTo("PENDING_VERIFICATION");

    // The owner reads the new mail and retakes the account with a registration of their own.
    clock.advance(COOLDOWN);
    registerWith(email, phone(), VICTIM_PASSWORD);
    assertThat(verifyWith(email, awaitOtp(email, 4), VICTIM_PASSWORD).getStatus()).isEqualTo(200);
    assertThat(login(email, ATTACKER_PASSWORD).getStatus()).isEqualTo(401);
  }

  @Test
  void aReplacementInsideTheCooldownEndsTheCodeAndWaitsForAResend() throws Exception {
    String email = email();
    registerWith(email, phone(), ATTACKER_PASSWORD);
    String oldCode = awaitOtp(email, 1);

    assertThat(registerWith(email, phone(), VICTIM_PASSWORD).getStatus()).isEqualTo(201);

    assertThat(liveChallenges(email)).isZero();
    assertThat(verifyWith(email, oldCode, ATTACKER_PASSWORD).getStatus()).isEqualTo(400);
    assertThat(verifyWith(email, oldCode, VICTIM_PASSWORD).getStatus()).isEqualTo(400);
    assertMailCountStays(email, EmailKind.OTP, 1);

    clock.advance(COOLDOWN);
    resend(email);
    assertThat(verifyWith(email, awaitOtp(email, 2), VICTIM_PASSWORD).getStatus()).isEqualTo(200);
  }

  // Conflicts -------------------------------------------------------------------------------------

  @Test
  void aPhoneOwnedByAnotherAccountReplacesNothingAndNotifiesThatOwner() throws Exception {
    String owner = email();
    String takenPhone = phone();
    register(owner, takenPhone);
    String email = email();
    String ownPhone = phone();
    registerWith(email, ownPhone, ATTACKER_PASSWORD);
    String code = awaitOtp(email, 1);
    String hash = hashOf(email);
    clock.advance(COOLDOWN);

    assertThat(registerWith(email, takenPhone, VICTIM_PASSWORD).getStatus()).isEqualTo(201);

    assertThat(hashOf(email)).isEqualTo(hash);
    assertThat(phoneOf(email)).isEqualTo(ownPhone);
    assertThat(phoneOf(owner)).isEqualTo(takenPhone);
    awaitMailCount(owner, EmailKind.EXISTING_ACCOUNT_NOTICE, 1);
    assertMailCountStays(email, EmailKind.OTP, 1);
    assertThat(verifyWith(email, code, ATTACKER_PASSWORD).getStatus()).isEqualTo(200);
  }

  @Test
  void reRegisteringThePendingAccountsOwnPhoneStillReplacesThePassword() throws Exception {
    String email = email();
    String phone = phone();
    registerWith(email, phone, ATTACKER_PASSWORD);
    awaitOtp(email, 1);
    clock.advance(COOLDOWN);

    registerWith(email, phone, VICTIM_PASSWORD);

    assertThat(phoneOf(email)).isEqualTo(phone);
    assertThat(verifyWith(email, awaitOtp(email, 2), VICTIM_PASSWORD).getStatus()).isEqualTo(200);
  }

  @Test
  void anAccountPastVerificationChangesNothing() throws Exception {
    for (String status : List.of("ACTIVE", "FROZEN", "SUSPENDED")) {
      String email = activeUser();
      jdbc.update("UPDATE auth.users SET status = ? WHERE email = ?", status, email);
      String hash = hashOf(email);
      String phone = phoneOf(email);
      clock.advance(COOLDOWN);

      assertThat(registerWith(email, phone(), VICTIM_PASSWORD).getStatus()).isEqualTo(201);

      assertThat(statusOf(email)).isEqualTo(status);
      assertThat(hashOf(email)).isEqualTo(hash);
      assertThat(phoneOf(email)).isEqualTo(phone);
      awaitMailCount(email, EmailKind.EXISTING_ACCOUNT_NOTICE, 1);
      assertMailCountStays(email, EmailKind.OTP, 1);
    }
  }

  // The password on verify --------------------------------------------------------------------

  @Test
  void aMissingOrWrongPasswordIsTheIdenticalInvalidOtpAndAuditsTheRealReason() throws Exception {
    String email = email();
    register(email, phone());
    String code = awaitOtp(email, 1);

    String missing = UUID.randomUUID().toString();
    MockHttpServletResponse withoutPassword =
        postWithCorrelation(
            "verify-otp",
            Map.of("identifier", email, "purpose", "REGISTRATION", "otp", code),
            missing);
    String wrong = UUID.randomUUID().toString();
    MockHttpServletResponse withWrongPassword =
        postWithCorrelation(
            "verify-otp",
            Map.of(
                "identifier",
                email,
                "purpose",
                "REGISTRATION",
                "otp",
                code,
                "password",
                "not-the-password"),
            wrong);
    MockHttpServletResponse withWrongCode = verify(email, "REGISTRATION", wrongCode(code));

    assertThat(withoutPassword.getStatus()).isEqualTo(400);
    assertThat(stable(withoutPassword))
        .contains("INVALID_OTP")
        .isEqualTo(stable(withWrongPassword))
        .isEqualTo(stable(withWrongCode));
    assertThat(auditReason("OTP_VERIFY", missing)).isEqualTo("WRONG_PASSWORD");
    assertThat(auditReason("OTP_VERIFY", wrong)).isEqualTo("WRONG_PASSWORD");
    assertThat(statusOf(email)).isEqualTo("PENDING_VERIFICATION");
    // The challenge is still live, so the owner can still finish.
    assertThat(verify(email, "REGISTRATION", code).getStatus()).isEqualTo(200);
  }

  @Test
  void aWrongPasswordConsumesAttemptsSoItCannotBeGuessedWithoutLimit() throws Exception {
    String email = email();
    register(email, phone());
    String code = awaitOtp(email, 1);

    for (int i = 1; i <= 4; i++) {
      assertThat(verifyWith(email, code, "guess-number-" + i).getStatus()).isEqualTo(400);
      assertThat(attempts(email)).isEqualTo(i);
    }
    assertThat(verifyWith(email, code, "guess-number-5").getStatus()).isEqualTo(400);

    assertThat(liveChallenges(email)).isZero();
    assertThat(verifyWith(email, code, PASSWORD).getStatus()).isEqualTo(400);
    assertThat(statusOf(email)).isEqualTo("PENDING_VERIFICATION");
  }

  @Test
  void aPasswordIsNormalisedWhenVerifiedLikeEverywhereElse() throws Exception {
    String email = email();
    registerWith(email, phone(), "café-long-password");
    assertThat(verifyWith(email, awaitOtp(email, 1), "café-long-password").getStatus())
        .isEqualTo(200);
  }

  @Test
  void aResetCodeStillNeedsNoPasswordAndAnExtraOneIsIgnored() throws Exception {
    String email = activeUser();
    postJson("forgot-password", Map.of("email", email));
    MockHttpServletResponse verified =
        postJson(
            "verify-otp",
            Map.of(
                "identifier",
                email,
                "purpose",
                "PASSWORD_RESET",
                "otp",
                awaitOtp(email, 2),
                "password",
                "anything-at-all"));
    assertThat(verified.getStatus()).isEqualTo(200);
    assertThat(verified.getContentAsString()).contains("resetToken");
  }

  @Test
  void theSuccessIsAuditedExactlyOnce() throws Exception {
    String email = email();
    register(email, phone());
    String correlationId = UUID.randomUUID().toString();
    postWithCorrelation(
        "verify-otp", verifyBody(email, "REGISTRATION", awaitOtp(email, 1)), correlationId);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_logs WHERE action = 'OTP_VERIFY'"
                    + " AND correlation_id = ? AND result = 'SUCCESS'",
                Integer.class,
                correlationId))
        .isEqualTo(1);
  }

  // Races -----------------------------------------------------------------------------------------

  private <T> List<T> concurrently(List<java.util.concurrent.Callable<T>> tasks) throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    List<T> results = new ArrayList<>();
    try (ExecutorService pool = Executors.newFixedThreadPool(tasks.size())) {
      List<Future<T>> futures = new ArrayList<>();
      for (var task : tasks) {
        futures.add(
            pool.submit(
                () -> {
                  start.await();
                  return task.call();
                }));
      }
      start.countDown();
      for (Future<T> future : futures) {
        results.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      }
    }
    return results;
  }

  @Test
  void concurrentReplacementsLeaveOneConsistentAccount() throws Exception {
    String email = email();
    registerWith(email, phone(), ATTACKER_PASSWORD);
    clock.advance(COOLDOWN);
    List<String> phones = new ArrayList<>();
    List<java.util.concurrent.Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      String phone = phone();
      phones.add(phone);
      String password = "concurrent-password-" + i;
      tasks.add(() -> registerWith(email, phone, password).getStatus());
    }

    assertThat(concurrently(tasks)).containsOnly(201);

    assertThat(historyOf(email)).containsExactly(hashOf(email));
    assertThat(phones).contains(phoneOf(email));
    // The last to run cancelled the live code and, inside the cooldown, issued none.
    assertThat(liveChallenges(email)).isLessThanOrEqualTo(1);
  }

  @Test
  void twoPendingAccountsSwappingPhonesConcurrentlyNeitherFailNorDeadlock() throws Exception {
    for (int round = 0; round < 5; round++) {
      String first = email();
      String second = email();
      String firstPhone = phone();
      String secondPhone = phone();
      register(first, firstPhone);
      register(second, secondPhone);

      assertThat(
              concurrently(
                  List.of(
                      () -> registerWith(first, secondPhone, VICTIM_PASSWORD).getStatus(),
                      () -> registerWith(second, firstPhone, VICTIM_PASSWORD).getStatus())))
          .containsOnly(201);

      // Each number stays with exactly one account.
      assertThat(usersWithPhone(firstPhone)).isEqualTo(1);
      assertThat(usersWithPhone(secondPhone)).isEqualTo(1);
    }
  }

  @Test
  void aVerifyRacingAReplacementEitherActivatesOrIsRefusedButNeverFails() throws Exception {
    for (int round = 0; round < 5; round++) {
      String email = email();
      registerWith(email, phone(), ATTACKER_PASSWORD);
      String code = awaitOtp(email, 1);
      clock.advance(COOLDOWN);

      List<Integer> statuses =
          concurrently(
              List.of(
                  () -> verifyWith(email, code, ATTACKER_PASSWORD).getStatus(),
                  () -> registerWith(email, phone(), VICTIM_PASSWORD).getStatus()));

      assertThat(statuses.get(0)).isIn(200, 400);
      assertThat(statuses.get(1)).isEqualTo(201);
      if (statuses.get(0) == 400) {
        // The replacement won, so the old password can never activate.
        assertThat(statusOf(email)).isEqualTo("PENDING_VERIFICATION");
        assertThat(verifyWith(email, code, ATTACKER_PASSWORD).getStatus()).isEqualTo(400);
      }
    }
  }

  // The owner's notice ----------------------------------------------------------------------------

  @Test
  void theNoticeGoesAtMostOncePerIntervalAndTheResponseStaysTheSame() throws Exception {
    String email = activeUser();
    MockHttpServletResponse first = registerWith(email, phone(), VICTIM_PASSWORD);
    awaitMailCount(email, EmailKind.EXISTING_ACCOUNT_NOTICE, 1);

    MockHttpServletResponse second = registerWith(email, phone(), VICTIM_PASSWORD);
    clock.advance(Duration.ofMinutes(59));
    MockHttpServletResponse third = registerWith(email, phone(), VICTIM_PASSWORD);

    assertThat(third.getStatus()).isEqualTo(201);
    assertThat(stable(second)).isEqualTo(stable(first));
    assertThat(stable(third)).isEqualTo(stable(first));
    assertMailCountStays(email, EmailKind.EXISTING_ACCOUNT_NOTICE, 1);

    clock.advance(Duration.ofMinutes(1));
    registerWith(email, phone(), VICTIM_PASSWORD);
    awaitMailCount(email, EmailKind.EXISTING_ACCOUNT_NOTICE, 2);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM auth.owner_notices n JOIN auth.users u ON u.id = n.user_id"
                    + " WHERE u.email = ?",
                Integer.class,
                email))
        .isEqualTo(1);
  }

  @Test
  void concurrentRegistersSendOneNotice() throws Exception {
    String email = activeUser();
    List<java.util.concurrent.Callable<Integer>> tasks = new ArrayList<>();
    for (int i = 0; i < 6; i++) {
      tasks.add(() -> registerWith(email, phone(), VICTIM_PASSWORD).getStatus());
    }

    assertThat(concurrently(tasks)).containsOnly(201);

    awaitMailCount(email, EmailKind.EXISTING_ACCOUNT_NOTICE, 1);
    assertMailCountStays(email, EmailKind.EXISTING_ACCOUNT_NOTICE, 1);
  }

  @Test
  void aPhoneOwnersNoticeIsLimitedTheSameWay() throws Exception {
    String owner = activeUser();
    String phone = phoneOf(owner);

    register(email(), phone);
    register(email(), phone);

    awaitMailCount(owner, EmailKind.EXISTING_ACCOUNT_NOTICE, 1);
    assertMailCountStays(owner, EmailKind.EXISTING_ACCOUNT_NOTICE, 1);
  }
}
