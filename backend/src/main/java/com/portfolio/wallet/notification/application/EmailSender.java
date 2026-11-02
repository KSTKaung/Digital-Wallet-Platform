package com.portfolio.wallet.notification.application;

/**
 * Port to the mail transport: one blocking delivery that throws when it fails. Business code does
 * not call it directly; it uses {@link EmailDispatcher}, which adds after-commit, asynchronous
 * sending and failure handling.
 */
public interface EmailSender {

  void send(EmailMessage message);
}
