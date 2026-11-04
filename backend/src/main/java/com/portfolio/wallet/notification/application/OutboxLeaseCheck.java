package com.portfolio.wallet.notification.application;

import java.time.Duration;
import java.util.Map;

/**
 * Startup check (owner, 2026-11-04, R3, S1): the outbox lease must be longer than one send can take
 * by its SMTP timeouts, plus {@link OutboxProperties#leaseSafetyMargin()}. The application does not
 * start otherwise.
 *
 * <ul>
 *   <li>The protocol ({@code spring.mail.protocol}, default {@code smtp}) must be {@code smtp} or
 *       {@code smtps}; its own JavaMail keys are checked ({@code mail.smtp.*} or {@code
 *       mail.smtps.*}). Any other protocol fails, because its timeouts are unknown.
 *   <li>A JNDI mail session ({@code spring.mail.jndi-name}) fails, because its timeouts are not in
 *       this configuration.
 *   <li>Each of the connection, read and write timeouts must be set, numeric, positive and at most
 *       {@link Integer#MAX_VALUE} (in milliseconds): a missing or zero timeout means a send without
 *       a time bound, and JavaMail ignores a value beyond an int, which means the same (T1).
 *   <li>Required: connection + {@value #READ_ROUND_TRIPS} × read + write + the address lookup
 *       timeout (T2) + margin, computed without overflow (an overflow fails the check); the lease
 *       must be strictly longer.
 * </ul>
 *
 * <p>This keeps an ordinary send inside its lease; it does not make a slow or paused worker safe on
 * its own. That is the claim's fencing token (MVP-ADR-25, R1): a result from an older claim is
 * ignored.
 */
public final class OutboxLeaseCheck {

  /**
   * How many server replies one send may wait for, each bounded by the read timeout: EHLO, STARTTLS
   * or the TLS handshake, MAIL FROM, RCPT TO, DATA, the end of the data, QUIT, and one more as a
   * margin (S1).
   */
  static final int READ_ROUND_TRIPS = 8;

  private OutboxLeaseCheck() {}

  /**
   * @param protocol {@code spring.mail.protocol}; null means the default {@code smtp}
   * @param jndiName {@code spring.mail.jndi-name}, or null
   * @param mailProperties {@code spring.mail.properties}, keyed without the prefix
   * @return a marker that the check passed
   * @throws IllegalStateException if the configuration cannot bound a send, or the lease is too
   *     short
   */
  static OutboxLeaseCheck verify(
      OutboxProperties outbox,
      String protocol,
      String jndiName,
      Map<String, String> mailProperties) {
    if (jndiName != null && !jndiName.isBlank()) {
      throw new IllegalStateException(
          "spring.mail.jndi-name is not supported: the outbox lease must be checked against the"
              + " mail timeouts, which a JNDI session does not show");
    }
    String scheme = protocol == null || protocol.isBlank() ? "smtp" : protocol.strip();
    if (!scheme.equals("smtp") && !scheme.equals("smtps")) {
      throw new IllegalStateException(
          "spring.mail.protocol must be smtp or smtps for the outbox lease check");
    }
    String prefix = "mail." + scheme + ".";
    long connect = millis(mailProperties, prefix + "connectiontimeout");
    long read = millis(mailProperties, prefix + "timeout");
    long write = millis(mailProperties, prefix + "writetimeout");
    Duration required;
    try {
      long sendMillis =
          Math.addExact(Math.addExact(connect, Math.multiplyExact(READ_ROUND_TRIPS, read)), write);
      required =
          Duration.ofMillis(sendMillis)
              .plus(outbox.addressLookupTimeout())
              .plus(outbox.leaseSafetyMargin());
    } catch (ArithmeticException e) {
      throw new IllegalStateException(
          "the mail and lookup timeouts are too large to bound one send of the outbox");
    }
    if (outbox.lease().compareTo(required) <= 0) {
      throw new IllegalStateException(
          "app.notification.outbox.lease ("
              + outbox.lease()
              + ") must be longer than connection + "
              + READ_ROUND_TRIPS
              + " x read + write timeout plus the address lookup timeout and the safety margin ("
              + required
              + ")");
    }
    return new OutboxLeaseCheck();
  }

  private static long millis(Map<String, String> mailProperties, String key) {
    String value = mailProperties.get(key);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "spring.mail.properties." + key + " must be set: the outbox lease must cover a send");
    }
    long millis;
    try {
      millis = Long.parseLong(value.strip());
    } catch (NumberFormatException e) {
      throw new IllegalStateException(
          "spring.mail.properties." + key + " must be a number of milliseconds");
    }
    if (millis <= 0) {
      throw new IllegalStateException(
          "spring.mail.properties." + key + " must be positive: 0 means no time bound");
    }
    // JavaMail reads the timeouts as int and ignores a larger value, which means no timeout (T1).
    if (millis > Integer.MAX_VALUE) {
      throw new IllegalStateException(
          "spring.mail.properties." + key + " must be at most " + Integer.MAX_VALUE + " ms");
    }
    return millis;
  }
}
