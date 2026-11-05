package com.portfolio.wallet.wallet.domain;

import com.portfolio.wallet.common.money.Money;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The request hash of an idempotent money request (documentations/02 §1.4; doc 07 §7): SHA-256 of a
 * canonical form of the <em>validated</em> request, so formatting differences ({@code 10.0} and
 * {@code 10.00}, JSON key order, whitespace) never produce a false {@code 409
 * IDEMPOTENCY_KEY_REUSED}.
 *
 * <p>Canonical form, version 1: UTF-8 lines, each ending in {@code \n}, in this order:
 *
 * <pre>
 * wallet-idempotency-v1
 * operation=DEPOSIT
 * customer=0b0e…            (lower-case UUID)
 * amount=10.00              ({@link Money#amount()} as a plain string: always scale 2)
 * currency=THB
 * &lt;field&gt;=&lt;length&gt;:&lt;value&gt;   (each extra field, in the order added; length in UTF-16 units)
 * &lt;field&gt;=null              (an extra field without a value)
 * </pre>
 *
 * The amount comes from {@link Money}, which refuses a non-zero third decimal instead of rounding,
 * so an amount such as {@code 10.001} never reaches this class. Text values are hashed exactly as
 * validated (no trimming or Unicode normalisation here); the length prefix keeps a value that
 * contains {@code \n} or {@code =} unambiguous, and {@code null} differs from the empty string.
 * Changing this form changes every hash: give it a new version line.
 */
public final class RequestFingerprint {

  static final String VERSION_LINE = "wallet-idempotency-v1";

  private static final Pattern FIELD_NAME = Pattern.compile("[a-z][A-Za-z0-9]{0,39}");
  private static final Set<String> RESERVED = Set.of("operation", "customer", "amount", "currency");

  private final Operation operation;
  private final UUID customerId;
  private final String canonicalForm;
  private final String sha256Hex;

  private RequestFingerprint(Operation operation, UUID customerId, String canonicalForm) {
    this.operation = operation;
    this.customerId = customerId;
    this.canonicalForm = canonicalForm;
    this.sha256Hex = sha256Hex(canonicalForm);
  }

  public static Builder builder(Operation operation, UUID customerId, Money amount) {
    return new Builder(operation, customerId, amount);
  }

  public Operation operation() {
    return operation;
  }

  public UUID customerId() {
    return customerId;
  }

  /** The canonical form; it holds request values, so it is never logged. */
  String canonicalForm() {
    return canonicalForm;
  }

  /** 64 lower-case hex characters ({@code request_hash varchar(64)}). */
  public String sha256Hex() {
    return sha256Hex;
  }

  private static String sha256Hex(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // Every Java platform must provide SHA-256.
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /** Builds a fingerprint; extra fields are part of the hash in the order they are added. */
  public static final class Builder {

    private final Operation operation;
    private final UUID customerId;
    private final Money amount;
    private final Map<String, String> fields = new LinkedHashMap<>();

    private Builder(Operation operation, UUID customerId, Money amount) {
      this.operation = Objects.requireNonNull(operation, "operation");
      this.customerId = Objects.requireNonNull(customerId, "customerId");
      this.amount = Objects.requireNonNull(amount, "amount");
    }

    /**
     * Adds a validated request field, such as the deposit's client reference.
     *
     * @param name a constant of the caller: a lower camel-case identifier, not one of the fixed
     *     fields, used once
     * @param value the validated value, or null when the request has none
     */
    public Builder field(String name, String value) {
      Objects.requireNonNull(name, "name");
      if (!FIELD_NAME.matcher(name).matches() || RESERVED.contains(name)) {
        throw new IllegalArgumentException("invalid fingerprint field name");
      }
      if (fields.containsKey(name)) {
        throw new IllegalArgumentException("fingerprint field added twice");
      }
      fields.put(name, value);
      return this;
    }

    public RequestFingerprint build() {
      StringBuilder form = new StringBuilder(128);
      line(form, VERSION_LINE);
      line(form, "operation=" + operation.name());
      line(form, "customer=" + customerId);
      line(form, "amount=" + amount.amount().toPlainString());
      line(form, "currency=" + amount.currency().getCurrencyCode());
      fields.forEach(
          (name, value) ->
              line(form, name + "=" + (value == null ? "null" : value.length() + ":" + value)));
      return new RequestFingerprint(operation, customerId, form.toString());
    }

    private static void line(StringBuilder form, String text) {
      form.append(text).append('\n');
    }
  }
}
