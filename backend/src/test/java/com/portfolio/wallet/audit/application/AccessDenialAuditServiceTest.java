package com.portfolio.wallet.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.portfolio.wallet.common.security.AccessDenial;
import com.portfolio.wallet.support.MutableClock;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class AccessDenialAuditServiceTest {

  private static final Duration WINDOW = Duration.ofMinutes(1);

  private final AuditService audit = mock(AuditService.class);
  private final MutableClock clock = new MutableClock();
  private final AccessDenialAuditService service =
      new AccessDenialAuditService(audit, new AuditProperties(WINDOW, 1000), clock);

  private AuditEntry recorded(AccessDenial denial) {
    service.record(denial);
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).recordIndependently(entry.capture());
    verify(audit, never()).record(ArgumentMatchers.any());
    return entry.getValue();
  }

  private static AccessDenial denied(UUID actor) {
    return new AccessDenial(AccessDenial.Kind.ACCESS_DENIED, actor, false, "GET", "/api/x", null);
  }

  @Test
  void aCustomerDenialInItsOwnTransaction() {
    UUID actor = UUID.randomUUID();
    AuditEntry entry = recorded(denied(actor));

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

  // The 403 audit cap (owner, 2026-11-03; doc 08 §4B.2 Q12) --------------------------------------

  @Test
  void onlyTheFirstDenialPerUserInTheWindowIsAudited() {
    UUID actor = UUID.randomUUID();

    service.record(denied(actor));
    service.record(denied(actor));
    clock.advance(WINDOW.minusMillis(1));
    service.record(denied(actor));

    verify(audit, times(1)).recordIndependently(any());
  }

  @Test
  void theNextWindowIsAuditedAgain() {
    UUID actor = UUID.randomUUID();
    service.record(denied(actor));
    clock.advance(WINDOW);

    service.record(denied(actor));

    verify(audit, times(2)).recordIndependently(any());
  }

  @Test
  void eachUserHasTheirOwnWindow() {
    service.record(denied(UUID.randomUUID()));
    service.record(denied(UUID.randomUUID()));

    verify(audit, times(2)).recordIndependently(any());
  }

  @Test
  void refusalsWithoutAUserAndCsrfRefusalsAreNotCapped() {
    service.record(denied(null));
    service.record(denied(null));
    AccessDenial csrf =
        new AccessDenial(AccessDenial.Kind.CSRF_REFUSED, null, false, "POST", "/x", null);
    service.record(csrf);
    service.record(csrf);

    verify(audit, times(4)).recordIndependently(any());
  }

  @Test
  void aFailedWriteDoesNotHideTheUsersNextRefusal() {
    UUID actor = UUID.randomUUID();
    doThrow(new IllegalStateException("database down")).when(audit).recordIndependently(any());
    assertThatThrownBy(() -> service.record(denied(actor)))
        .isInstanceOf(IllegalStateException.class);
    reset(audit);

    service.record(denied(actor));

    verify(audit).recordIndependently(any());
  }

  // J1, J8: a refusal left unaudited is still traceable in the log ------------------------------

  @Test
  void aRefusalInsideTheWindowIsLoggedWithUserMethodAndPath(CapturedOutput output) {
    UUID actor = UUID.randomUUID();
    service.record(denied(actor));
    int logStart = output.getOut().length();

    service.record(
        new AccessDenial(
            AccessDenial.Kind.ACCESS_DENIED, actor, false, "POST", "/api/v1/admin/x", null));

    String log = output.getOut().substring(logStart);
    assertThat(log)
        .contains("not audited, the user's audit row for this window exists")
        .contains("userId=" + actor)
        .contains("method=POST")
        .contains("path=/api/v1/admin/x")
        .doesNotContain("maximum number of users");
    verify(audit, times(1)).recordIndependently(any());
  }

  @Test
  void aRefusalWhileTheCapIsFullHasItsOwnAccurateMessage(CapturedOutput output) {
    AccessDenialAuditService full =
        new AccessDenialAuditService(audit, new AuditProperties(WINDOW, 1), clock);
    full.record(denied(UUID.randomUUID()));
    UUID untracked = UUID.randomUUID();
    int logStart = output.getOut().length();

    full.record(
        new AccessDenial(AccessDenial.Kind.ACCESS_DENIED, untracked, true, "GET", "/a/b", null));

    String log = output.getOut().substring(logStart);
    assertThat(log)
        .contains("not audited, the audit cap already tracks its maximum number of users")
        .contains("userId=" + untracked)
        .contains("method=GET")
        .contains("path=/a/b")
        .doesNotContain("audit row for this window exists");
    verify(audit, times(1)).recordIndependently(any());
  }

  @Test
  void theLoggedPathIsCutLikeTheAuditedOne(CapturedOutput output) {
    UUID actor = UUID.randomUUID();
    service.record(denied(actor));
    int logStart = output.getOut().length();

    service.record(
        new AccessDenial(
            AccessDenial.Kind.ACCESS_DENIED, actor, false, "GET", "/" + "y".repeat(500), null));

    assertThat(output.getOut().substring(logStart))
        .contains("y".repeat(199))
        .doesNotContain("y".repeat(200));
  }
}
