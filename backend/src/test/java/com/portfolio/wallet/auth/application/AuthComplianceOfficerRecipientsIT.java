package com.portfolio.wallet.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.notification.application.ComplianceOfficerRecipients;
import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The active compliance officers against PostgreSQL (doc 08 §4C.3): role {@code COMPLIANCE_OFFICER}
 * and status {@code ACTIVE} only, in PostgreSQL's ascending uuid order. Other tests' accounts share
 * the table, so only this test's ids are checked.
 */
class AuthComplianceOfficerRecipientsIT extends DatabaseIntegrationTest {

  @Autowired ComplianceOfficerRecipients recipients;

  private UUID account(String role, String status) throws Exception {
    UUID id = UUID.randomUUID();
    insert(
        "auth.users",
        row(
            "id",
            id,
            "email",
            "user-" + id + "@example.test",
            "phone_number",
            "CUSTOMER".equals(role) ? "+" + randomDigits(15) : null,
            "status",
            status,
            "role",
            role));
    return id;
  }

  @Test
  void onlyActiveComplianceOfficersInAscendingIdOrder() throws Exception {
    UUID first = account("COMPLIANCE_OFFICER", "ACTIVE");
    UUID second = account("COMPLIANCE_OFFICER", "ACTIVE");
    List<UUID> others =
        List.of(
            account("COMPLIANCE_OFFICER", "FROZEN"),
            account("COMPLIANCE_OFFICER", "SUSPENDED"),
            account("COMPLIANCE_OFFICER", "PENDING_VERIFICATION"),
            account("ADMIN", "ACTIVE"),
            account("CUSTOMER", "ACTIVE"));

    List<UUID> active = recipients.activeComplianceOfficers();

    assertThat(active).contains(first, second).doesNotContainAnyElementsOf(others);
    // PostgreSQL's uuid order (bytewise), not Java's signed comparison.
    assertThat(active)
        .isEqualTo(
            jdbc.queryForList(
                "SELECT id FROM auth.users WHERE role = 'COMPLIANCE_OFFICER' AND status = 'ACTIVE'"
                    + " ORDER BY id",
                UUID.class));
  }
}
