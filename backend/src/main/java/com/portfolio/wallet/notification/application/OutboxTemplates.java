package com.portfolio.wallet.notification.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The texts of the outbox emails (documentations/06 MVP-ADR-25), rendered when a row is sent.
 *
 * <p>They carry no KYC content (no name, document or reason) and no reason, balance or wallet
 * number, because email is not a protected channel and a forwarded or intercepted message should
 * reveal nothing about the review or the account; the rejection reason is shown only after signing
 * in (documentations/08 §4B.2 Q13). Each states the time of the change in the business zone (owner,
 * 2026-11-03, K2): delivery order is not guaranteed, and the time tells the reader which change
 * came last.
 *
 * <p>A kind declares the template values it accepts; today none takes any. The outbox refuses
 * anything else, so nothing unplanned (an address, KYC content) can reach a row.
 */
final class OutboxTemplates {

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm:ss", Locale.ENGLISH);

  private record Template(String subject, Set<String> keys, Function<String, String> body) {}

  private static final Map<EmailKind, Template> TEMPLATES = new EnumMap<>(EmailKind.class);

  static {
    TEMPLATES.put(
        EmailKind.KYC_APPROVED,
        new Template(
            "Your identity verification was approved",
            Set.of(),
            time ->
                "Your identity verification was approved on "
                    + time
                    + " and your wallet is ready. Sign in to start using it."));
    TEMPLATES.put(
        EmailKind.KYC_REJECTED,
        new Template(
            "Your identity verification was not approved",
            Set.of(),
            time ->
                "Your identity verification application was not approved (decided on "
                    + time
                    + "). Sign in to see the reason and, if you wish, submit a new application."));
    TEMPLATES.put(
        EmailKind.WALLET_FROZEN,
        new Template(
            "Your wallet has been frozen",
            Set.of(),
            time ->
                "Your wallet was frozen by our staff on "
                    + time
                    + ". If you have any questions, please contact support."));
    TEMPLATES.put(
        EmailKind.WALLET_UNFROZEN,
        new Template(
            "Your wallet has been unfrozen",
            Set.of(),
            time ->
                "Your wallet was unfrozen by our staff on "
                    + time
                    + " and can be used again. If you have any questions, please contact"
                    + " support."));
  }

  private OutboxTemplates() {}

  /**
   * @return the template values {@code kind} accepts, or empty if {@code kind} is not sent through
   *     the outbox (OTP, registration and account-lock emails stay direct)
   */
  static Optional<Set<String>> keys(EmailKind kind) {
    return Optional.ofNullable(TEMPLATES.get(kind)).map(Template::keys);
  }

  /** {@code occurredAt} as the emails show it, for example {@code 3 November 2026 at 14:05:09}. */
  static String time(Instant occurredAt, ZoneId zone) {
    return TIME.format(occurredAt.atZone(zone)) + " (" + zone.getId() + " time)";
  }

  static EmailMessage render(EmailKind kind, String to, Instant occurredAt, ZoneId zone) {
    Template template = TEMPLATES.get(kind);
    if (template == null) {
      throw new IllegalArgumentException("no outbox template for " + kind);
    }
    return new EmailMessage(
        kind, to, template.subject(), template.body().apply(time(occurredAt, zone)));
  }
}
