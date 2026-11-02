package com.portfolio.wallet.auth.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.auth.domain.PasswordPolicy;
import com.portfolio.wallet.auth.persistence.CredentialRepository;
import com.portfolio.wallet.auth.persistence.UserRepository;
import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionOperations;

/** The runner against the real schema; environment handling is in the unit test (doc 08 Q8). */
class StaffBootstrapIT extends DatabaseIntegrationTest {

  private static final String STAFF_PASSWORD = "a-staff-password-long";

  @Autowired UserRepository users;
  @Autowired CredentialRepository credentials;
  @Autowired PasswordHasher passwords;
  @Autowired PasswordPolicy policy;
  @Autowired AuditService audit;
  @Autowired TransactionOperations tx;
  @Autowired Clock clock;

  private void runWith(String email, String password) {
    new StaffBootstrapRunner(
            new MockEnvironment()
                .withProperty(StaffBootstrapRunner.ADMIN_EMAIL, email)
                .withProperty(StaffBootstrapRunner.ADMIN_PASSWORD, password),
            users,
            credentials,
            passwords,
            policy,
            audit,
            tx,
            clock)
        .run(null);
  }

  private static String staffEmail() {
    return "staff-" + UUID.randomUUID() + "@example.test";
  }

  private Map<String, Object> account(String email) {
    return jdbc.queryForMap(
        "SELECT u.id, u.status, u.role, u.phone_number, c.password_hash FROM auth.users u"
            + " JOIN auth.credentials c ON c.user_id = u.id WHERE u.email = ?",
        email);
  }

  @Test
  void createsAnActiveStaffAccountWithNoPhoneAndAuditsItAsSystem() {
    String email = staffEmail();
    runWith(email, STAFF_PASSWORD);

    Map<String, Object> created = account(email);
    assertThat(created.get("status")).isEqualTo("ACTIVE");
    assertThat(created.get("role")).isEqualTo("ADMIN");
    assertThat(created.get("phone_number")).isNull();
    assertThat(passwords.matches(STAFF_PASSWORD, (String) created.get("password_hash"))).isTrue();
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM audit.audit_logs WHERE action = 'STAFF_BOOTSTRAP'"
                    + " AND actor_type = 'SYSTEM' AND result = 'SUCCESS' AND resource_id = ?",
                Integer.class,
                created.get("id").toString()))
        .isEqualTo(1);
  }

  @Test
  void aSecondRunChangesNothing() {
    String email = staffEmail();
    runWith(email, STAFF_PASSWORD);
    Map<String, Object> first = account(email);

    runWith(email, "a-different-staff-password");

    assertThat(account(email)).isEqualTo(first);
  }

  @Test
  void anEmailHeldByACustomerIsRefusedWithoutMixingRoles() throws Exception {
    Map<String, Object> customer = validUser();
    insert("auth.users", customer);
    credentials.insert(
        (UUID) customer.get("id"), passwords.hash("a-customer-password"), clock.instant());
    String email = (String) customer.get("email");
    Map<String, Object> before = account(email);

    runWith(email, STAFF_PASSWORD);

    assertThat(account(email)).isEqualTo(before);
    assertThat(before.get("role")).isEqualTo("CUSTOMER");
  }
}
