package com.portfolio.wallet.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

class EmailMessageTest {

  @Test
  void toStringHidesRecipientSubjectAndBody() {
    String text =
        new EmailMessage(EmailKind.OTP, "user@example.test", "Subject-x", "code 123456").toString();

    assertThat(text).contains("OTP").doesNotContain("user@example.test", "Subject-x", "123456");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"", " ", "a@example.test\nBcc: b@example.test", "a@example.test\r"})
  void headerFieldsMustBeANonBlankSingleLine(String value) {
    assertThatThrownBy(() -> new EmailMessage(EmailKind.OTP, value, "Subject", "body"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new EmailMessage(EmailKind.OTP, "a@example.test", value, "body"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void smtpAdapterMapsTheMessageAndTheConfiguredSender() {
    JavaMailSender mailSender = mock(JavaMailSender.class);
    EmailSender adapter =
        new SmtpEmailSender(mailSender, new NotificationProperties("from@example.test"));

    adapter.send(new EmailMessage(EmailKind.OTP, "to@example.test", "Subject", "Body"));

    ArgumentCaptor<SimpleMailMessage> mail = ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mailSender).send(mail.capture());
    assertThat(mail.getValue().getFrom()).isEqualTo("from@example.test");
    assertThat(mail.getValue().getTo()).containsExactly("to@example.test");
    assertThat(mail.getValue().getSubject()).isEqualTo("Subject");
    assertThat(mail.getValue().getText()).isEqualTo("Body");
  }
}
