package com.portfolio.wallet.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * The startup check that the outbox lease covers a send (owner, 2026-11-04, R3, S1): longer than
 * connection + 8 × read + write timeout plus the safety margin, for the protocol in use, or the
 * context does not start.
 */
class OutboxLeaseCheckTest {

  private static Map<String, String> timeouts(String scheme) {
    return Map.of(
        "mail." + scheme + ".connectiontimeout", "5000",
        "mail." + scheme + ".timeout", "5000",
        "mail." + scheme + ".writetimeout", "5000");
  }

  private static OutboxProperties outbox(Duration lease) {
    return new OutboxProperties(
        5,
        List.of(
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            Duration.ofHours(1)),
        50,
        Duration.ofSeconds(10),
        lease,
        Duration.ofSeconds(30),
        Duration.ofSeconds(5));
  }

  private static OutboxProperties outbox(Duration lease, Duration lookup) {
    return new OutboxProperties(
        5,
        List.of(
            Duration.ofMinutes(1),
            Duration.ofMinutes(5),
            Duration.ofMinutes(15),
            Duration.ofHours(1)),
        50,
        Duration.ofSeconds(10),
        lease,
        Duration.ofSeconds(30),
        lookup);
  }

  /** T2: the address lookup timeout is part of the required lease. */
  @Test
  void theLookupTimeoutCountsTowardsTheRequiredLease() {
    Map<String, String> mail = timeouts("smtp");
    // 5 + 40 + 5 + 60 (lookup) + 30 = 140 s.
    assertThatThrownBy(
            () ->
                OutboxLeaseCheck.verify(
                    outbox(Duration.ofSeconds(140), Duration.ofSeconds(60)), "smtp", null, mail))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("PT2M20S");
    assertThat(
            OutboxLeaseCheck.verify(
                outbox(Duration.ofMillis(140_001), Duration.ofSeconds(60)), "smtp", null, mail))
        .isNotNull();
  }

  /** T1: JavaMail ignores a timeout beyond an int, so it is refused; no overflow anywhere. */
  @Test
  void aTimeoutBeyondAnIntOrAnOverflowingSumIsRefused() {
    for (String value :
        new String[] {
          String.valueOf(1L << 60),
          String.valueOf((long) Integer.MAX_VALUE + 1),
          "9223372036854775807"
        }) {
      Map<String, String> mail = new HashMap<>(timeouts("smtp"));
      mail.put("mail.smtp.timeout", value);
      assertThatThrownBy(
              () -> OutboxLeaseCheck.verify(outbox(Duration.ofDays(365_000)), "smtp", null, mail))
          .as(value)
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("mail.smtp.timeout");
    }
    // Integer.MAX_VALUE itself is accepted as a timeout; a lease long enough then passes.
    Map<String, String> max = new HashMap<>(timeouts("smtp"));
    max.put("mail.smtp.timeout", String.valueOf(Integer.MAX_VALUE));
    assertThat(OutboxLeaseCheck.verify(outbox(Duration.ofDays(365)), "smtp", null, max))
        .isNotNull();
    assertThatThrownBy(
            () -> OutboxLeaseCheck.verify(outbox(Duration.ofDays(198)), "smtp", null, max))
        .isInstanceOf(IllegalStateException.class);
    // A lookup timeout so large the sum overflows is a startup failure, not an exception.
    assertThatThrownBy(
            () ->
                OutboxLeaseCheck.verify(
                    outbox(Duration.ofDays(1), Duration.ofSeconds(Long.MAX_VALUE)),
                    "smtp",
                    null,
                    timeouts("smtp")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("too large");
  }

  /** Today's configuration: 5 + 8 × 5 + 5 + 5 (lookup) + 30 = 85 s, below the 5-minute lease. */
  @Test
  void theMvpLeaseCoversTheMvpTimeoutsAndMargin() {
    assertThat(OutboxLeaseCheck.verify(outbox(Duration.ofMinutes(5)), null, null, timeouts("smtp")))
        .isNotNull();
    assertThat(OutboxLeaseCheck.READ_ROUND_TRIPS).isEqualTo(8);
  }

  /** The lease must be strictly longer than connect + 8 × read + write + margin. */
  @Test
  void theFormulasBoundaryIsExclusive() {
    Map<String, String> mail =
        Map.of(
            "mail.smtp.connectiontimeout", "1000",
            "mail.smtp.timeout", "2000",
            "mail.smtp.writetimeout", "3000");
    // 1 + 8 x 2 + 3 + 5 (lookup) + 30 = 55 s.
    assertThatThrownBy(
            () -> OutboxLeaseCheck.verify(outbox(Duration.ofSeconds(55)), "smtp", null, mail))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("app.notification.outbox.lease")
        .hasMessageContaining("PT55S");
    assertThat(OutboxLeaseCheck.verify(outbox(Duration.ofMillis(55_001)), "smtp", null, mail))
        .isNotNull();
  }

  /** With smtps the mail.smtps.* keys are the ones checked; the smtp keys do not count. */
  @Test
  void smtpsChecksItsOwnKeys() {
    assertThat(
            OutboxLeaseCheck.verify(
                outbox(Duration.ofMinutes(5)), "smtps", null, timeouts("smtps")))
        .isNotNull();
    assertThatThrownBy(
            () ->
                OutboxLeaseCheck.verify(
                    outbox(Duration.ofMinutes(5)), "smtps", null, timeouts("smtp")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("mail.smtps.connectiontimeout");
    Map<String, String> slow = new HashMap<>(timeouts("smtps"));
    slow.put("mail.smtps.timeout", "40000");
    // 5 + 8 x 40 + 5 + 5 + 30 = 365 s > 5 min.
    assertThatThrownBy(
            () -> OutboxLeaseCheck.verify(outbox(Duration.ofMinutes(5)), "smtps", null, slow))
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest
  @ValueSource(strings = {"smtp", "smtps"})
  void aMissingZeroOrInvalidTimeoutIsRefused(String scheme) {
    for (String key :
        List.of(
            "mail." + scheme + ".connectiontimeout",
            "mail." + scheme + ".timeout",
            "mail." + scheme + ".writetimeout")) {
      for (String value : new String[] {null, "", "0", "-1", "five"}) {
        Map<String, String> mail = new HashMap<>(timeouts(scheme));
        mail.put(key, value);
        assertThatThrownBy(
                () -> OutboxLeaseCheck.verify(outbox(Duration.ofMinutes(5)), scheme, null, mail))
            .as("%s = %s", key, value)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining(key);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"imap", "pop3", "smtp+tls", "SMTP"})
  void anyOtherProtocolIsRefused(String protocol) {
    assertThatThrownBy(
            () ->
                OutboxLeaseCheck.verify(
                    outbox(Duration.ofMinutes(5)), protocol, null, timeouts("smtp")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.mail.protocol");
  }

  @Test
  void aJndiMailSessionIsRefused() {
    assertThatThrownBy(
            () ->
                OutboxLeaseCheck.verify(
                    outbox(Duration.ofMinutes(5)),
                    "smtp",
                    "java:comp/env/mail/Session",
                    timeouts("smtp")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("spring.mail.jndi-name");
  }

  // The check as wired: the context does not start unless the lease covers a send --------------

  private final ApplicationContextRunner context =
      new ApplicationContextRunner()
          .withUserConfiguration(NotificationConfiguration.class)
          .withPropertyValues(
              "app.mail.from=no-reply@example.test",
              "app.notification.outbox.max-attempts=5",
              "app.notification.outbox.backoff=1m,5m,15m,60m",
              "app.notification.outbox.batch-size=50",
              "app.notification.outbox.poll-interval=10s",
              "app.notification.outbox.lease-safety-margin=30s",
              "app.notification.outbox.address-lookup-timeout=5s",
              "spring.mail.properties[mail.smtp.connectiontimeout]=5000",
              "spring.mail.properties[mail.smtp.timeout]=5000",
              "spring.mail.properties[mail.smtp.writetimeout]=5000");

  @Test
  void theContextStartsWithAValidLease() {
    context
        .withPropertyValues("app.notification.outbox.lease=5m")
        .run(started -> assertThat(started).hasNotFailed().hasSingleBean(OutboxLeaseCheck.class));
    context
        .withPropertyValues(
            "app.notification.outbox.lease=5m",
            "spring.mail.protocol=smtps",
            "spring.mail.properties[mail.smtps.connectiontimeout]=5000",
            "spring.mail.properties[mail.smtps.timeout]=5000",
            "spring.mail.properties[mail.smtps.writetimeout]=5000")
        .run(started -> assertThat(started).hasNotFailed());
  }

  @Test
  void theContextFailsToStartWhenTheLeaseCannotCoverASend() {
    // 85 s required by the 5 s timeouts, the 5 s lookup timeout and the 30 s margin.
    context
        .withPropertyValues("app.notification.outbox.lease=85s")
        .run(started -> assertThat(started).hasFailed());
    context
        .withPropertyValues(
            "app.notification.outbox.lease=5m", "spring.mail.properties[mail.smtp.timeout]=")
        .run(started -> assertThat(started).hasFailed());
    context
        .withPropertyValues("app.notification.outbox.lease=5m", "spring.mail.protocol=smtps")
        .run(started -> assertThat(started).hasFailed());
    context
        .withPropertyValues("app.notification.outbox.lease=5m", "spring.mail.protocol=imap")
        .run(started -> assertThat(started).hasFailed());
    context
        .withPropertyValues(
            "app.notification.outbox.lease=5m", "spring.mail.jndi-name=java:comp/env/mail/Session")
        .run(started -> assertThat(started).hasFailed());
  }
}
