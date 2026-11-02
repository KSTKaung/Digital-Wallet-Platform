package com.portfolio.wallet.auth.application;

import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.auth.domain.EmailAddress;
import com.portfolio.wallet.auth.domain.PasswordPolicy;
import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.auth.persistence.AccountRow;
import com.portfolio.wallet.auth.persistence.CredentialRepository;
import com.portfolio.wallet.auth.persistence.UserRepository;
import com.portfolio.wallet.common.security.RawEnvironment;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Creates staff accounts from optional environment variables (documentations/08 §4A.2 Q8). Runs
 * only when they are set, and never changes an existing account.
 *
 * <p>A bad value is a deployment error and stops startup, naming the variable and never its value.
 * An email already held by another account is refused but does not stop startup: anyone can
 * register a customer, and that must not be a way to keep the application down.
 */
@Component
public class StaffBootstrapRunner implements ApplicationRunner {

  static final String ADMIN_EMAIL = "STAFF_ADMIN_EMAIL";
  static final String ADMIN_PASSWORD = "STAFF_ADMIN_PASSWORD";
  static final String COMPLIANCE_EMAIL = "STAFF_COMPLIANCE_EMAIL";
  static final String COMPLIANCE_PASSWORD = "STAFF_COMPLIANCE_PASSWORD";

  private static final Logger log = LoggerFactory.getLogger(StaffBootstrapRunner.class);
  private static final String ACTION = "STAFF_BOOTSTRAP";
  private static final String REASON_EMAIL_TAKEN = "EMAIL_TAKEN";
  private static final Pattern EMAIL_SHAPE = Pattern.compile("[^@\\s]+@[^@\\s]+");

  /** The size of {@code auth.users.email}. */
  private static final int MAX_EMAIL_LENGTH = 254;

  private final ConfigurableEnvironment environment;
  private final UserRepository users;
  private final CredentialRepository credentials;
  private final PasswordHasher passwords;
  private final PasswordPolicy policy;
  private final AuditService audit;
  private final TransactionOperations tx;
  private final Clock clock;

  public StaffBootstrapRunner(
      ConfigurableEnvironment environment,
      UserRepository users,
      CredentialRepository credentials,
      PasswordHasher passwords,
      PasswordPolicy policy,
      AuditService audit,
      TransactionOperations tx,
      Clock clock) {
    this.environment = environment;
    this.users = users;
    this.credentials = credentials;
    this.passwords = passwords;
    this.policy = policy;
    this.audit = audit;
    this.tx = tx;
    this.clock = clock;
  }

  @Override
  public void run(ApplicationArguments args) {
    provision(Role.ADMIN, ADMIN_EMAIL, ADMIN_PASSWORD);
    provision(Role.COMPLIANCE_OFFICER, COMPLIANCE_EMAIL, COMPLIANCE_PASSWORD);
  }

  private void provision(Role role, String emailVariable, String passwordVariable) {
    String email = value(emailVariable);
    String password = value(passwordVariable);
    if (email == null && password == null) {
      return;
    }
    if (email == null || password == null) {
      throw new IllegalStateException(
          "Set both " + emailVariable + " and " + passwordVariable + ", or neither");
    }
    String canonical = EmailAddress.canonical(email);
    if (canonical.length() > MAX_EMAIL_LENGTH || !EMAIL_SHAPE.matcher(canonical).matches()) {
      throw new IllegalStateException(emailVariable + " is not a valid email address");
    }
    if (!policy.isValidLength(password)) {
      throw new IllegalStateException(
          passwordVariable
              + " must have "
              + policy.minLength()
              + " to "
              + policy.maxLength()
              + " characters");
    }

    Optional<AccountRow> existing = users.findAccountByEmail(canonical);
    if (existing.isPresent()) {
      if (existing.get().role() == role) {
        log.info("Staff account already exists, left unchanged: role={}", role);
      } else {
        refuse(role, emailVariable);
      }
      return;
    }
    String hash = passwords.hash(password);
    if (!Boolean.TRUE.equals(tx.execute(status -> create(canonical, role, hash)))) {
      refuse(role, emailVariable); // Taken between the read and the insert.
    }
  }

  private boolean create(String canonical, Role role, String hash) {
    Optional<UUID> created = users.insertStaffIfAbsent(canonical, role);
    created.ifPresent(
        id -> {
          credentials.insert(id, hash, clock.instant());
          audit.record(
              AuthAudit.system(ACTION, AuthAudit.USER, id.toString(), AuditResult.SUCCESS)
                  .withNewValue(Map.of("role", role.name())));
          log.info("Created a staff account: role={}", role);
        });
    return created.isPresent();
  }

  private void refuse(Role role, String emailVariable) {
    log.error("Refused a staff account: {} belongs to another account", emailVariable);
    audit.recordIndependently(
        AuthAudit.system(ACTION, AuthAudit.USER, null, AuditResult.DENIED)
            .withNewValue(Map.of("reason", REASON_EMAIL_TAKEN, "role", role.name())));
  }

  private String value(String variable) {
    String value = RawEnvironment.lookup(environment).apply(variable);
    return value == null || value.isBlank() ? null : value;
  }
}
