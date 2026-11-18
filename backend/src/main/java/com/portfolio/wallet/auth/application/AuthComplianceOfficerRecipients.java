package com.portfolio.wallet.auth.application;

import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.auth.persistence.UserRepository;
import com.portfolio.wallet.notification.application.ComplianceOfficerRecipients;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The notification module's {@link ComplianceOfficerRecipients} over {@code auth.users}
 * (documentations/08 §4C.3: the port listing active {@code COMPLIANCE_OFFICER} user ids). Active
 * means status {@code ACTIVE}, the only status that can sign in ({@code UserStatus}); a temporary
 * sign-in lockout after failed passwords does not count, because the officer can sign in again when
 * it ends, long before a reminded transfer expires. The module direction stays auth → notification.
 */
@Component
class AuthComplianceOfficerRecipients implements ComplianceOfficerRecipients {

  private final UserRepository users;

  AuthComplianceOfficerRecipients(UserRepository users) {
    this.users = users;
  }

  @Override
  public List<UUID> activeComplianceOfficers() {
    return users.findActiveIdsByRole(Role.COMPLIANCE_OFFICER);
  }
}
