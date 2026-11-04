package com.portfolio.wallet.notification.application;

/**
 * Port to the mail transport: one blocking delivery that throws when it fails. Other modules do not
 * call it directly: the auth module's direct emails go through {@link EmailDispatcher}, and
 * business emails through {@link NotificationOutbox} and {@link OutboxDispatcher}.
 */
public interface EmailSender {

  void send(EmailMessage message);
}
