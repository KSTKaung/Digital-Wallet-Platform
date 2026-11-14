package com.portfolio.wallet.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.auth.persistence.UserRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The notification module's port listing the active compliance officers (doc 08 §4C.3). */
class AuthComplianceOfficerRecipientsTest {

  private final UserRepository users = mock(UserRepository.class);

  @Test
  void theActiveComplianceOfficersAreTheRecipients() {
    List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID());
    when(users.findActiveIdsByRole(Role.COMPLIANCE_OFFICER)).thenReturn(ids);

    assertThat(new AuthComplianceOfficerRecipients(users).activeComplianceOfficers())
        .isEqualTo(ids);
  }
}
