package com.portfolio.wallet.notification.application;

import com.portfolio.wallet.audit.application.ActorType;
import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.common.correlation.CorrelationId;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Sends mail after the caller's transaction commits, on a separate thread, so an SMTP problem can
 * neither fail nor slow the request and a rolled-back action sends nothing (documentations/06
 * MVP-ADR-23). The executor is Spring Boot's {@code applicationTaskExecutor}, which uses virtual
 * threads because {@code spring.threads.virtual.enabled} is set.
 *
 * <p>Only for the direct emails of the auth module (OTP, registration notice, account lock), which
 * stay direct under MVP-ADR-25: a code is time-limited and a user who does not get one asks again.
 * Business emails (KYC decisions, wallet freeze and unfreeze) go through {@link NotificationOutbox}
 * instead, which has a stable event id and retries.
 *
 * <p>A failed send is logged and audited as {@code EMAIL_SEND_FAILED} with the {@link EmailKind}
 * and the exception's class only. The exception's message and stack trace are left out, because
 * mail exceptions quote recipient addresses.
 */
@Service
public class EmailDispatcher {

  private static final Logger log = LoggerFactory.getLogger(EmailDispatcher.class);

  private final EmailSender sender;
  private final AuditService auditService;
  private final TaskExecutor executor;

  public EmailDispatcher(
      EmailSender sender,
      AuditService auditService,
      @Qualifier("applicationTaskExecutor") TaskExecutor executor) {
    this.sender = sender;
    this.auditService = auditService;
    this.executor = executor;
  }

  /** Queues {@code message} for after the current commit, or at once without a transaction. */
  public void sendAfterCommit(EmailMessage message) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              dispatch(message);
            }
          });
    } else {
      dispatch(message);
    }
  }

  private void dispatch(EmailMessage message) {
    String correlationId = CorrelationId.current().orElse(null);
    try {
      executor.execute(() -> deliver(message, correlationId));
    } catch (RuntimeException e) {
      // For example a rejected task during shutdown; the request has already succeeded.
      reportFailure(message, e);
    }
  }

  // Runs on the worker thread, which does not inherit the request's MDC.
  private void deliver(EmailMessage message, String correlationId) {
    if (correlationId != null) {
      MDC.put(CorrelationId.MDC_KEY, correlationId);
    }
    try {
      sender.send(message);
    } catch (RuntimeException e) {
      reportFailure(message, e);
    } finally {
      MDC.remove(CorrelationId.MDC_KEY);
    }
  }

  private void reportFailure(EmailMessage message, RuntimeException failure) {
    String failureType = failure.getClass().getSimpleName();
    log.warn("Email send failed: kind={}, failure={}", message.kind(), failureType);
    try {
      auditService.recordIndependently(
          AuditEntry.of(ActorType.SYSTEM, "EMAIL_SEND_FAILED", "EMAIL", AuditResult.FAILURE)
              .withResourceId(message.kind().name())
              .withNewValue(Map.of("failure", failureType)));
    } catch (RuntimeException auditFailure) {
      log.error(
          "Could not audit the failed email send: kind={}, failure={}",
          message.kind(),
          auditFailure.getClass().getSimpleName());
    }
  }
}
