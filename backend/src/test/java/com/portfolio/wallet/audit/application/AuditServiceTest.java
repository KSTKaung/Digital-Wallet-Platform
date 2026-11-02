package com.portfolio.wallet.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.portfolio.wallet.audit.persistence.AuditLogRepository;
import com.portfolio.wallet.audit.persistence.AuditLogRow;
import com.portfolio.wallet.common.correlation.CorrelationId;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import tools.jackson.databind.json.JsonMapper;

class AuditServiceTest {

  private final AuditLogRepository repository = mock(AuditLogRepository.class);
  private final AuditService service = new AuditService(repository, JsonMapper.builder().build());

  @AfterEach
  void clearMdc() {
    MDC.clear();
  }

  private AuditLogRow insertedRow() {
    ArgumentCaptor<AuditLogRow> row = ArgumentCaptor.forClass(AuditLogRow.class);
    verify(repository).insert(row.capture());
    return row.getValue();
  }

  @Test
  void mapsEveryFieldAndTakesTheCorrelationIdFromTheRequest() throws Exception {
    MDC.put(CorrelationId.MDC_KEY, "corr-1");
    UUID actor = UUID.randomUUID();

    service.record(
        AuditEntry.of(ActorType.STAFF, "FREEZE", "WALLET", AuditResult.DENIED)
            .withActor(actor)
            .withResourceId("wallet-1")
            .withClientIp(InetAddress.getByName("192.0.2.1"))
            .withOldValue(Map.of("status", "ACTIVE"))
            .withNewValue(Map.of("status", "FROZEN")));

    AuditLogRow row = insertedRow();
    assertThat(row.actorId()).isEqualTo(actor);
    assertThat(row.actorType()).isEqualTo("STAFF");
    assertThat(row.action()).isEqualTo("FREEZE");
    assertThat(row.resource()).isEqualTo("WALLET");
    assertThat(row.resourceId()).isEqualTo("wallet-1");
    assertThat(row.result()).isEqualTo("DENIED");
    assertThat(row.clientIp()).isEqualTo("192.0.2.1");
    assertThat(row.correlationId()).isEqualTo("corr-1");
    assertThat(row.oldValue()).isEqualTo("{\"status\":\"ACTIVE\"}");
    assertThat(row.newValue()).isEqualTo("{\"status\":\"FROZEN\"}");
  }

  @Test
  void optionalFieldsStayNull() {
    service.recordIndependently(
        AuditEntry.of(ActorType.SYSTEM, "EXPIRE", "TRANSFER", AuditResult.SUCCESS));

    AuditLogRow row = insertedRow();
    assertThat(row.actorId()).isNull();
    assertThat(row.resourceId()).isNull();
    assertThat(row.clientIp()).isNull();
    assertThat(row.correlationId()).isNull();
    assertThat(row.oldValue()).isNull();
    assertThat(row.newValue()).isNull();
  }

  /** The inet column rejects a zone suffix, which would otherwise lose the audit row. */
  @Test
  void ipv6ZoneIsRemoved() throws Exception {
    byte[] linkLocal = InetAddress.getByName("fe80::1").getAddress();

    service.record(
        AuditEntry.of(ActorType.ANONYMOUS, "LOGIN", "USER", AuditResult.FAILURE)
            .withClientIp(Inet6Address.getByAddress(null, linkLocal, 3)));

    assertThat(insertedRow().clientIp()).doesNotContain("%").startsWith("fe80:");
  }
}
