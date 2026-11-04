package com.portfolio.wallet.admin.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.audit.application.AuditProperties;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * The {@code 403} audit cap over real HTTP (owner, 2026-11-03; documentations/08 §4B.2 Q12): at
 * most one {@code ACCESS_DENIED} row per user per window, the rest logged only; each refusal still
 * gets the same {@code 403}. Refusals by the URL rules and by {@code @PreAuthorize} share the cap.
 */
@ExtendWith(OutputCaptureExtension.class)
class AccessDeniedAuditCapIT extends AdminFlowSupport {

  @Autowired AuditProperties auditProperties;

  private int deniedRows(UUID actor) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit.audit_logs WHERE action = 'ACCESS_DENIED' AND actor_id = ?",
        Integer.class,
        actor);
  }

  @Test
  void theConfiguredWindowIsTheOwnersMinute() {
    assertThat(auditProperties.accessDeniedWindow()).isEqualTo(Duration.ofMinutes(1));
  }

  @Test
  void repeatedRefusalsOfOneUserWriteOneRowAndAreLogged(CapturedOutput output) throws Exception {
    Account officer = officer();
    int logStart = output.getOut().length();

    for (int i = 0; i < 5; i++) {
      MockHttpServletResponse response =
          postAs(
              "/api/v1/admin/customers/" + UUID.randomUUID() + "/freeze",
              Map.of("reason", "reason"),
              officer.token());
      assertThat(response.getStatus()).isEqualTo(403);
      assertThat(errorCode(response)).isEqualTo("FORBIDDEN");
    }
    // A different path and a different refusal layer count against the same cap. The query string
    // is not logged.
    assertThat(getAs("/api/v1/wallet?marker=q-7781", officer.token()).getStatus()).isEqualTo(403);

    assertThat(deniedRows(officer.id())).isEqualTo(1);
    String log = output.getOut().substring(logStart);
    // Six refusals: one audited, five logged only (n occurrences split into n + 1 parts).
    assertThat(log.split("not audited, the user's audit row for this window exists", -1))
        .hasSize(5 + 1);
    // J1: each unaudited refusal names the user, the method and the path, and nothing else.
    assertThat(log.split("userId=" + officer.id(), -1)).hasSize(5 + 1);
    assertThat(log)
        .contains("method=POST, path=/api/v1/admin/customers/")
        .contains("method=GET, path=/api/v1/wallet")
        .doesNotContain(officer.email(), "q-7781", "marker", "Bearer", "\"reason\"")
        .doesNotContain("maximum number of users");
  }

  @Test
  void eachUserHasTheirOwnWindow() throws Exception {
    Account first = customer();
    Account second = customer();

    getAs(REVIEW, first.token());
    getAs(REVIEW, first.token());
    getAs(REVIEW, second.token());

    assertThat(deniedRows(first.id())).isEqualTo(1);
    assertThat(deniedRows(second.id())).isEqualTo(1);
  }
}
