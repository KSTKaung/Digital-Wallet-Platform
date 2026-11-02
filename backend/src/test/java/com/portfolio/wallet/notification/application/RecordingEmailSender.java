package com.portfolio.wallet.notification.application;

import com.portfolio.wallet.common.correlation.CorrelationId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.MDC;

/**
 * Test fake for the {@link EmailSender} port: records each message and the thread that delivered
 * it, or fails on request. Later tests read an OTP from {@link #messages()}.
 */
public class RecordingEmailSender implements EmailSender {

  public record Delivery(EmailMessage message, boolean onVirtualThread, String correlationId) {}

  private final List<Delivery> deliveries = new CopyOnWriteArrayList<>();
  private volatile boolean failing;

  @Override
  public void send(EmailMessage message) {
    if (failing) {
      throw new IllegalStateException("smtp down for " + message.to());
    }
    deliveries.add(
        new Delivery(message, Thread.currentThread().isVirtual(), MDC.get(CorrelationId.MDC_KEY)));
  }

  public List<Delivery> deliveries() {
    return List.copyOf(deliveries);
  }

  public List<EmailMessage> messages() {
    return deliveries.stream().map(Delivery::message).toList();
  }

  public void setFailing(boolean failing) {
    this.failing = failing;
  }

  public void clear() {
    deliveries.clear();
    failing = false;
  }
}
