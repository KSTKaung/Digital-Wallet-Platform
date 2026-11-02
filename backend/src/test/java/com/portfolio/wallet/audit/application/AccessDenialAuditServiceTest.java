package com.portfolio.wallet.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.portfolio.wallet.common.security.AccessDenial;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

class AccessDenialAuditServiceTest {

  private final AuditService audit = mock(AuditService.class);
  private final AccessDenialAuditService service = new AccessDenialAuditService(audit);

  private AuditEntry recorded(AccessDenial denial) {
    service.record(denial);
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).recordIndependently(entry.capture());
    verify(audit, never()).record(ArgumentMatchers.any());
    return entry.getValue();
  }

  @Test
  void aCustomerDenialInItsOwnTransaction() {
    UUID actor = UUID.randomUUID();
    AuditEntry entry =
        recorded(
            new AccessDenial(AccessDenial.Kind.ACCESS_DENIED, actor, false, "GET", "/api/x", null));

    assertThat(entry.action()).isEqualTo("ACCESS_DENIED");
    assertThat(entry.actorType()).isEqualTo(ActorType.CUSTOMER);
    assertThat(entry.actorId()).isEqualTo(actor);
    assertThat(entry.result()).isEqualTo(AuditResult.DENIED);
    assertThat(entry.newValue()).containsEntry("method", "GET").containsEntry("path", "/api/x");
  }

  @Test
  void staffAndAnonymousActors() {
    assertThat(
            recorded(
                    new AccessDenial(
                        AccessDenial.Kind.ACCESS_DENIED,
                        UUID.randomUUID(),
                        true,
                        "GET",
                        "/x",
                        null))
                .actorType())
        .isEqualTo(ActorType.STAFF);
  }

  @Test
  void aCsrfRefusalHasNoActorAndAnOverlongPathIsCut() {
    AuditEntry entry =
        recorded(
            new AccessDenial(
                AccessDenial.Kind.CSRF_REFUSED, null, false, "POST", "/" + "x".repeat(500), null));

    assertThat(entry.action()).isEqualTo("CSRF_REFUSED");
    assertThat(entry.actorType()).isEqualTo(ActorType.ANONYMOUS);
    assertThat((String) entry.newValue().get("path")).hasSize(200);
  }
}
