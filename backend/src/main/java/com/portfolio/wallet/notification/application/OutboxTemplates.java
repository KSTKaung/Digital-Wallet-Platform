package com.portfolio.wallet.notification.application;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
 * <p>Money emails (MVP-ADR-24 amendment "Emails (Q9)") state the amount, the currency and the
 * server reference ({@link MoneyEmailValues}), and nothing else: never a balance, a name, a wallet
 * number or the description, and nothing about risk (a held transfer's email names no score, rule
 * or review reason).
 *
 * <p>A kind declares the template values it takes; the outbox refuses any other set, so nothing
 * unplanned (an address, KYC content) can reach a row, and rendering refuses a row whose values do
 * not match (that attempt fails and is retried, MVP-ADR-25).
 */
final class OutboxTemplates {

  private static final DateTimeFormatter TIME =
      DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm:ss", Locale.ENGLISH);

  @FunctionalInterface
  private interface Body {
    String render(String time, Map<String, String> values);
  }

  private record Template(String subject, Set<String> keys, Body body) {}

  private static final Map<EmailKind, Template> TEMPLATES = new EnumMap<>(EmailKind.class);

  static {
    TEMPLATES.put(
        EmailKind.KYC_APPROVED,
        new Template(
            "Your identity verification was approved",
            Set.of(),
            (time, values) ->
                "Your identity verification was approved on "
                    + time
                    + " and your wallet is ready. Sign in to start using it."));
    TEMPLATES.put(
        EmailKind.KYC_REJECTED,
        new Template(
            "Your identity verification was not approved",
            Set.of(),
            (time, values) ->
                "Your identity verification application was not approved (decided on "
                    + time
                    + "). Sign in to see the reason and, if you wish, submit a new application."));
    TEMPLATES.put(
        EmailKind.WALLET_FROZEN,
        new Template(
            "Your wallet has been frozen",
            Set.of(),
            (time, values) ->
                "Your wallet was frozen by our staff on "
                    + time
                    + ". If you have any questions, please contact support."));
    TEMPLATES.put(
        EmailKind.WALLET_UNFROZEN,
        new Template(
            "Your wallet has been unfrozen",
            Set.of(),
            (time, values) ->
                "Your wallet was unfrozen by our staff on "
                    + time
                    + " and can be used again. If you have any questions, please contact"
                    + " support."));
    money(
        EmailKind.DEPOSIT_COMPLETED,
        "Your deposit was completed",
        (time, values) ->
            "Your deposit of "
                + money(values)
                + " was completed on "
                + time
                + "."
                + reference(values)
                + " If you did not make this deposit, please contact support.");
    money(
        EmailKind.WITHDRAWAL_COMPLETED,
        "Your withdrawal was completed",
        (time, values) ->
            "Your withdrawal of "
                + money(values)
                + " was completed on "
                + time
                + "."
                + reference(values)
                + " If you did not make this withdrawal, please contact support.");
    money(
        EmailKind.TRANSFER_SENT,
        "Your transfer was completed",
        (time, values) ->
            "Your transfer of "
                + money(values)
                + " was completed on "
                + time
                + "."
                + reference(values)
                + " If you did not make this transfer, please contact support.");
    money(
        EmailKind.TRANSFER_RECEIVED,
        "You received a transfer",
        (time, values) ->
            "You received a transfer of "
                + money(values)
                + " on "
                + time
                + "."
                + reference(values)
                + " Sign in to see the details.");
    money(
        EmailKind.TRANSFER_HELD,
        "Your transfer is waiting for review",
        (time, values) ->
            "Your transfer of "
                + money(values)
                + " was received on "
                + time
                + " and is waiting for review before it can be completed. Until then the"
                + " amount is held and the recipient has not received it. You can cancel the"
                + " transfer while it is waiting."
                + reference(values)
                + " Sign in to see its status.");
    money(
        EmailKind.TRANSFER_REJECTED,
        "Your transfer was not completed",
        (time, values) ->
            "Your transfer of "
                + money(values)
                + " was not completed (decided on "
                + time
                + "). The held amount is available again, and the recipient has not received"
                + " it."
                + reference(values)
                + " Sign in to see the reason.");
    money(
        EmailKind.TRANSFER_CANCELLED,
        "Your transfer was cancelled",
        (time, values) ->
            "Your transfer of "
                + money(values)
                + " was cancelled on "
                + time
                + ". The held amount is available again, and the recipient has not received"
                + " it."
                + reference(values)
                + " If you did not cancel this transfer, please contact support.");
    money(
        EmailKind.TRANSFER_EXPIRED,
        "Your transfer has expired",
        (time, values) ->
            "Your transfer of "
                + money(values)
                + " was not completed in time and expired on "
                + time
                + ". The held amount is available again, and the recipient has not received"
                + " it."
                + reference(values)
                + " You can send a new transfer if you wish.");
  }

  private OutboxTemplates() {}

  private static void money(EmailKind kind, String subject, Body body) {
    TEMPLATES.put(kind, new Template(subject, MoneyEmailValues.KEYS, body));
  }

  /** The amount with thousands separators and the currency, such as {@code 1,300.00 THB}. */
  private static String money(Map<String, String> values) {
    DecimalFormat format =
        new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ROOT));
    return format.format(new BigDecimal(values.get(MoneyEmailValues.AMOUNT)))
        + " "
        + values.get(MoneyEmailValues.CURRENCY);
  }

  private static String reference(Map<String, String> values) {
    return " Reference: " + values.get(MoneyEmailValues.REFERENCE) + ".";
  }

  /**
   * @return the template values {@code kind} takes, or empty if {@code kind} is not sent through
   *     the outbox (OTP, registration and account-lock emails stay direct)
   */
  static Optional<Set<String>> keys(EmailKind kind) {
    return Optional.ofNullable(TEMPLATES.get(kind)).map(Template::keys);
  }

  /** {@code occurredAt} as the emails show it, for example {@code 3 November 2026 at 14:05:09}. */
  static String time(Instant occurredAt, ZoneId zone) {
    return TIME.format(occurredAt.atZone(zone)) + " (" + zone.getId() + " time)";
  }

  /** A kind that takes no values. */
  static EmailMessage render(EmailKind kind, String to, Instant occurredAt, ZoneId zone) {
    return render(kind, to, occurredAt, zone, Map.of());
  }

  /**
   * @param values exactly the keys {@code kind} declares
   * @throws IllegalArgumentException for a kind with no outbox template, or values that do not
   *     match it; the message never quotes a value
   */
  static EmailMessage render(
      EmailKind kind, String to, Instant occurredAt, ZoneId zone, Map<String, String> values) {
    Template template = TEMPLATES.get(kind);
    if (template == null) {
      throw new IllegalArgumentException("no outbox template for " + kind);
    }
    Objects.requireNonNull(values, "values");
    if (!values.keySet().equals(template.keys())
        || values.values().stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException("template values do not match " + kind);
    }
    return new EmailMessage(
        kind, to, template.subject(), template.body().render(time(occurredAt, zone), values));
  }
}
