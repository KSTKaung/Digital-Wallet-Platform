package com.portfolio.wallet.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.portfolio.wallet.common.correlation.CorrelationId;
import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** The dispatcher in the real context: virtual threads, commit timing, audited failure. */
@Import(EmailDispatcherIT.FakeSender.class)
class EmailDispatcherIT extends DatabaseIntegrationTest {

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  @TestConfiguration
  static class FakeSender {
    @Bean
    @Primary
    RecordingEmailSender recordingEmailSender() {
      return new RecordingEmailSender();
    }
  }

  @Autowired EmailDispatcher dispatcher;
  @Autowired RecordingEmailSender sender;
  @Autowired PlatformTransactionManager transactionManager;

  private String correlationId;

  @BeforeEach
  void setUp() {
    sender.clear();
    correlationId = UUID.randomUUID().toString();
    MDC.put(CorrelationId.MDC_KEY, correlationId);
  }

  @AfterEach
  void clearMdc() {
    MDC.remove(CorrelationId.MDC_KEY);
  }

  private static EmailMessage message() {
    return new EmailMessage(EmailKind.OTP, "user@example.test", "Your code", "code 123456");
  }

  @Test
  void sendsAfterCommitOnAVirtualThreadWithTheCorrelationId() {
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              dispatcher.sendAfterCommit(message());
              // Still inside the transaction: the message must not have left yet.
              assertThat(sender.deliveries()).isEmpty();
            });

    await().atMost(TIMEOUT).until(() -> !sender.deliveries().isEmpty());
    RecordingEmailSender.Delivery delivery = sender.deliveries().getFirst();
    assertThat(delivery.onVirtualThread()).isTrue();
    assertThat(delivery.correlationId()).isEqualTo(correlationId);
  }

  @Test
  void aFailedSendIsAuditedInItsOwnTransactionWithoutContent() {
    sender.setFailing(true);

    dispatcher.sendAfterCommit(message());

    await()
        .atMost(TIMEOUT)
        .until(
            () ->
                jdbc.queryForObject(
                        "SELECT count(*) FROM audit.audit_logs WHERE correlation_id = ?",
                        Integer.class,
                        correlationId)
                    == 1);
    var row =
        jdbc.queryForMap(
            "SELECT action, resource_id, result, new_value::text AS new_value"
                + " FROM audit.audit_logs WHERE correlation_id = ?",
            correlationId);
    assertThat(row)
        .containsEntry("action", "EMAIL_SEND_FAILED")
        .containsEntry("resource_id", "OTP")
        .containsEntry("result", "FAILURE");
    assertThat(row.get("new_value").toString())
        .contains("IllegalStateException")
        .doesNotContain("user@example.test", "123456");
  }
}
