package com.portfolio.wallet.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Only hashes in the database; no OTP, password or token in logs or audit rows (AC 11). */
@ExtendWith(OutputCaptureExtension.class)
class SecretsAtRestIT extends AuthFlowSupport {

  private static final List<String> TABLES =
      List.of(
          "auth.users",
          "auth.credentials",
          "auth.password_history",
          "auth.sessions",
          "auth.refresh_tokens",
          "auth.otp_challenges",
          "auth.login_failure_counters",
          "auth.password_reset_tokens",
          "audit.audit_logs");

  /**
   * A six-digit code can appear by chance inside an id, a digest or a timestamp's fraction, so it
   * counts only where no hex digit, dot or dash touches it.
   */
  private static Pattern standalone(String code) {
    return Pattern.compile("(?<![0-9a-fA-F.\\-])" + code + "(?![0-9a-fA-F\\-])");
  }

  private String allRows() {
    StringBuilder all = new StringBuilder();
    for (String table : TABLES) {
      jdbc.queryForList("SELECT row_to_json(t)::text FROM " + table + " t", String.class)
          .forEach(row -> all.append(row).append('\n'));
    }
    return all.toString();
  }

  @Test
  void aFullFlowLeavesNoPlaintextInTheDatabaseOrTheLogs(CapturedOutput output) throws Exception {
    String email = email();
    register(email, phone());
    String otp = awaitOtp(email, 1);
    verify(email, "REGISTRATION", wrongCode(otp));
    verify(email, "REGISTRATION", otp);
    login(email, "another-wrong-password");
    Tokens first = loginTokens(email);
    Tokens second = tokens(refresh(first.refreshToken()));
    refresh(first.refreshToken());
    refresh(second.refreshToken());

    // Forgot, verify, a refused then an accepted reset, and a change.
    String resetPassword = "reset-to-this-password";
    String changedPassword = "changed-to-this-password";
    postJson("forgot-password", Map.of("email", email));
    String resetOtp = awaitOtp(email, 2);
    String resetToken =
        jsonMapper
            .readTree(verify(email, "PASSWORD_RESET", resetOtp).getContentAsString())
            .get("resetToken")
            .asString();
    postJson("reset-password", Map.of("resetToken", resetToken, "newPassword", PASSWORD));
    postJson("reset-password", Map.of("resetToken", resetToken, "newPassword", resetPassword));
    String changeAccess =
        jsonMapper
            .readTree(login(email, resetPassword).getContentAsString())
            .get("accessToken")
            .asString();
    mvc.perform(
        post(API + "change-password")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + changeAccess)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                jsonMapper.writeValueAsString(
                    Map.of("currentPassword", resetPassword, "newPassword", changedPassword))));

    List<String> secrets =
        new ArrayList<>(
            List.of(
                PASSWORD,
                "another-wrong-password",
                resetToken,
                resetPassword,
                changedPassword,
                changeAccess,
                first.accessToken(),
                first.refreshToken(),
                second.accessToken(),
                second.refreshToken()));

    String rows = allRows();
    String logs = output.getAll();
    assertThat(rows).contains("$argon2id$").doesNotContain(secrets);
    assertThat(logs).doesNotContain(secrets);
    assertThat(standalone(otp).matcher(rows).find()).isFalse();
    assertThat(standalone(otp).matcher(logs).find()).isFalse();
    assertThat(standalone(resetOtp).matcher(rows).find()).isFalse();
    assertThat(standalone(resetOtp).matcher(logs).find()).isFalse();

    // Audit rows hold no address, and no hash of a password.
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_logs"
                    + " WHERE coalesce(new_value::text, '') || coalesce(resource_id, '') LIKE ?"
                    + " OR coalesce(new_value::text, '') LIKE '%argon2%'",
                Integer.class, "%" + email + "%"))
        .isZero();
  }
}
