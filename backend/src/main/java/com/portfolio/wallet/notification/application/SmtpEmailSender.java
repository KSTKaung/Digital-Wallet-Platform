package com.portfolio.wallet.notification.application;

import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** Delivers mail over SMTP with Spring's {@link JavaMailSender}. */
@Component
class SmtpEmailSender implements EmailSender {

  private final JavaMailSender mailSender;
  private final NotificationProperties properties;

  SmtpEmailSender(JavaMailSender mailSender, NotificationProperties properties) {
    this.mailSender = mailSender;
    this.properties = properties;
  }

  @Override
  public void send(EmailMessage message) {
    SimpleMailMessage mail = new SimpleMailMessage();
    mail.setFrom(properties.from());
    mail.setTo(message.to());
    mail.setSubject(message.subject());
    mail.setText(message.body());
    mailSender.send(mail);
  }
}
