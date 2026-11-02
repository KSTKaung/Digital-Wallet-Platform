package com.portfolio.wallet.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.correlation.CorrelationId;
import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Audit rows and their transactions against real PostgreSQL (documentations/07 §9.3). */
class AuditServiceIT extends DatabaseIntegrationTest {

  private static final AuditEntry ENTRY =
      AuditEntry.of(ActorType.SYSTEM, "TEST_ACTION", "TEST", AuditResult.FAILURE);

  @Autowired AuditService auditService;
  @Autowired PlatformTransactionManager transactionManager;

  private String correlationId;

  @BeforeEach
  void newCorrelationId() {
    correlationId = UUID.randomUUID().toString();
    MDC.put(CorrelationId.MDC_KEY, correlationId);
  }

  @AfterEach
  void clearMdc() {
    MDC.remove(CorrelationId.MDC_KEY);
  }

  private int rowsForThisTest() {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit.audit_logs WHERE correlation_id = ?",
        Integer.class,
        correlationId);
  }

  private void inTransactionThatRollsBack(Runnable work) {
    TransactionTemplate template = new TransactionTemplate(transactionManager);
    template.executeWithoutResult(
        status -> {
          work.run();
          status.setRollbackOnly();
        });
  }

  @Test
  void writesEveryColumnAsTheRuntimeRole() throws Exception {
    UUID actor = UUID.randomUUID();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(status -> auditService.record(fullEntry(actor)));

    Map<String, Object> row =
        jdbc.queryForMap(
            "SELECT actor_id, actor_type, action, resource, resource_id, result,"
                + " host(client_ip) AS ip, old_value->>'status' AS old_status,"
                + " new_value->>'status' AS new_status, new_value->'tags'->>0 AS first_tag"
                + " FROM audit.audit_logs WHERE correlation_id = ?",
            correlationId);

    assertThat(row)
        .containsEntry("actor_id", actor)
        .containsEntry("actor_type", "CUSTOMER")
        .containsEntry("action", "PASSWORD_CHANGE")
        .containsEntry("resource", "USER")
        .containsEntry("resource_id", actor.toString())
        .containsEntry("result", "SUCCESS")
        .containsEntry("ip", "2001:db8::1")
        .containsEntry("old_status", "ACTIVE")
        .containsEntry("new_status", "FROZEN")
        .containsEntry("first_tag", "a");
  }

  private static AuditEntry fullEntry(UUID actor) {
    try {
      return AuditEntry.of(ActorType.CUSTOMER, "PASSWORD_CHANGE", "USER", AuditResult.SUCCESS)
          .withActor(actor)
          .withResourceId(actor.toString())
          .withClientIp(InetAddress.getByName("2001:db8::1"))
          .withOldValue(Map.of("status", "ACTIVE"))
          .withNewValue(Map.of("status", "FROZEN", "tags", List.of("a")));
    } catch (UnknownHostException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void recordRollsBackWithTheCallersTransaction() {
    inTransactionThatRollsBack(() -> auditService.record(ENTRY));

    assertThat(rowsForThisTest()).isZero();
  }

  @Test
  void recordIndependentlySurvivesTheCallersRollback() {
    inTransactionThatRollsBack(() -> auditService.recordIndependently(ENTRY));

    assertThat(rowsForThisTest()).isEqualTo(1);
  }

  @Test
  void recordIndependentlyWorksWithoutACallerTransaction() {
    auditService.recordIndependently(ENTRY);

    assertThat(rowsForThisTest()).isEqualTo(1);
  }

  @Test
  void recordRefusesToRunWithoutATransaction() {
    assertThatThrownBy(() -> auditService.record(ENTRY))
        .isInstanceOf(IllegalTransactionStateException.class);

    assertThat(rowsForThisTest()).isZero();
  }
}
