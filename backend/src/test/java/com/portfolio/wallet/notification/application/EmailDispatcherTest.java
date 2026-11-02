package com.portfolio.wallet.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.common.correlation.CorrelationId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionSynchronizationUtils;

/** After-commit dispatch and failure handling, on the calling thread for determinism. */
@ExtendWith(OutputCaptureExtension.class)
class EmailDispatcherTest {

  private static final String RECIPIENT = "recipient@example.test";
  private static final String BODY = "Your code is 123456";

  private static final EmailMessage MESSAGE =
      new EmailMessage(EmailKind.OTP, RECIPIENT, "Your code", BODY);

  private final RecordingEmailSender sender = new RecordingEmailSender();
  private final AuditService audit = mock(AuditService.class);
  private final EmailDispatcher dispatcher = new EmailDispatcher(sender, audit, Runnable::run);

  @AfterEach
  void cleanUp() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
    MDC.clear();
  }

  private AuditEntry auditedEntry() {
    ArgumentCaptor<AuditEntry> entry = ArgumentCaptor.forClass(AuditEntry.class);
    verify(audit).recordIndependently(entry.capture());
    return entry.getValue();
  }

  @Test
  void withoutATransactionTheMessageIsDispatchedAtOnce() {
    dispatcher.sendAfterCommit(MESSAGE);

    assertThat(sender.messages()).containsExactly(MESSAGE);
    verifyNoInteractions(audit);
  }

  @Test
  void insideATransactionNothingIsSentUntilTheCommit() {
    TransactionSynchronizationManager.initSynchronization();

    dispatcher.sendAfterCommit(MESSAGE);
    assertThat(sender.messages()).isEmpty();

    TransactionSynchronizationUtils.triggerAfterCommit();
    assertThat(sender.messages()).containsExactly(MESSAGE);
  }

  @Test
  void aRollbackSendsNothing() {
    TransactionSynchronizationManager.initSynchronization();

    dispatcher.sendAfterCommit(MESSAGE);
    List<TransactionSynchronization> registered =
        TransactionSynchronizationManager.getSynchronizations();
    assertThat(registered).hasSize(1);
    registered.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));

    assertThat(sender.messages()).isEmpty();
  }

  @Test
  void workerThreadRunsWithTheRequestsCorrelationIdAndClearsIt() {
    MDC.put(CorrelationId.MDC_KEY, "corr-1");

    dispatcher.sendAfterCommit(MESSAGE);

    assertThat(sender.deliveries().getFirst().correlationId()).isEqualTo("corr-1");
    assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
  }

  @Test
  void sendFailureDoesNotPropagateAndIsAuditedWithoutContent(CapturedOutput output) {
    sender.setFailing(true);

    assertThatCode(() -> dispatcher.sendAfterCommit(MESSAGE)).doesNotThrowAnyException();

    AuditEntry entry = auditedEntry();
    assertThat(entry.action()).isEqualTo("EMAIL_SEND_FAILED");
    assertThat(entry.resourceId()).isEqualTo("OTP");
    assertThat(entry.result()).isEqualTo(AuditResult.FAILURE);
    assertThat(entry.newValue()).containsOnly(Map.entry("failure", "IllegalStateException"));
    assertThat(output.getAll())
        .contains("Email send failed", "OTP")
        .doesNotContain(RECIPIENT, BODY);
  }

  @Test
  void aRejectedTaskDoesNotPropagateAndIsAudited() {
    TaskExecutor rejecting =
        task -> {
          throw new TaskRejectedException("shutting down");
        };
    EmailDispatcher rejected = new EmailDispatcher(sender, audit, rejecting);

    assertThatCode(() -> rejected.sendAfterCommit(MESSAGE)).doesNotThrowAnyException();

    assertThat(auditedEntry().newValue()).containsValue("TaskRejectedException");
  }

  @Test
  void anAuditFailureAfterASendFailureDoesNotPropagateEither(CapturedOutput output) {
    sender.setFailing(true);
    doThrow(new IllegalStateException("database down")).when(audit).recordIndependently(any());

    assertThatCode(() -> dispatcher.sendAfterCommit(MESSAGE)).doesNotThrowAnyException();

    assertThat(output.getAll()).contains("Could not audit").doesNotContain(RECIPIENT, BODY);
  }

  /** The real adapter against a closed port: the transport's own exception must stay contained. */
  @Test
  void realSmtpFailureDoesNotPropagateAndLogsNoContent(CapturedOutput output) {
    JavaMailSenderImpl closedPort = new JavaMailSenderImpl();
    closedPort.setHost("127.0.0.1");
    closedPort.setPort(1);
    EmailSender smtp =
        new SmtpEmailSender(closedPort, new NotificationProperties("from@example.test"));
    EmailDispatcher real = new EmailDispatcher(smtp, audit, Runnable::run);

    assertThatCode(() -> real.sendAfterCommit(MESSAGE)).doesNotThrowAnyException();

    assertThat(auditedEntry().action()).isEqualTo("EMAIL_SEND_FAILED");
    assertThat(output.getAll()).doesNotContain(RECIPIENT, BODY);
  }
}
