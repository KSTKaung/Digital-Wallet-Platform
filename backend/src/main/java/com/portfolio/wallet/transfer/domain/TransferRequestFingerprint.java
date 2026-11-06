package com.portfolio.wallet.transfer.domain;

import com.portfolio.wallet.common.fingerprint.CanonicalForm;
import com.portfolio.wallet.common.money.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * The request hash of an idempotent {@code POST /transfers} (documentations/02 §1.4; doc 07 §7; doc
 * 08 §4C.2 Q3): SHA-256 of a canonical form of the <em>validated</em> request, so formatting
 * differences ({@code 10.0} and {@code 10.00}, JSON key order, whitespace) never produce a false
 * {@code 409 IDEMPOTENCY_KEY_REUSED}.
 *
 * <p>Canonical form, version 1: UTF-8 lines, each ending in {@code \n}, in this order (written by
 * the shared {@link CanonicalForm}):
 *
 * <pre>
 * transfer-idempotency-v1
 * operation=TRANSFER
 * customer=0b0e…                        (the sender, lower-case UUID)
 * receiverWalletNumber=&lt;length&gt;:&lt;value&gt;   (as validated)
 * amount=10.00                          ({@link Money#amount()} as a plain string: always scale 2)
 * currency=THB
 * description=&lt;length&gt;:&lt;value&gt;            (as validated)
 * description=null                      (no description)
 * </pre>
 *
 * The amount comes from {@link Money}, which refuses a non-zero third decimal instead of rounding.
 * Text values are hashed exactly as validated (no trimming or Unicode normalisation here), so the
 * caller hashes the values it will store; {@code null} differs from the empty string. Changing this
 * form changes every transfer hash: give it a new version line.
 */
public final class TransferRequestFingerprint {

  /** The operation of every transfer idempotency record (V4 {@code ck_…_operation}). */
  public static final String OPERATION = "TRANSFER";

  static final String VERSION_LINE = "transfer-idempotency-v1";

  private final UUID customerId;
  private final String canonicalForm;
  private final String sha256Hex;

  private TransferRequestFingerprint(UUID customerId, String canonicalForm) {
    this.customerId = customerId;
    this.canonicalForm = canonicalForm;
    this.sha256Hex = CanonicalForm.sha256Hex(canonicalForm);
  }

  /**
   * @param customerId the authenticated sender
   * @param receiverWalletNumber the validated recipient wallet number from the request body
   * @param amount the validated amount and currency
   */
  public static Builder builder(UUID customerId, String receiverWalletNumber, Money amount) {
    return new Builder(customerId, receiverWalletNumber, amount);
  }

  public String operation() {
    return OPERATION;
  }

  /** The sender. */
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

  /** Names nothing from the request: the form holds the description and the wallet number. */
  @Override
  public String toString() {
    return "TransferRequestFingerprint[" + VERSION_LINE + "]";
  }

  /** Builds a fingerprint; the description is optional. */
  public static final class Builder {

    private final UUID customerId;
    private final String receiverWalletNumber;
    private final Money amount;
    private String description;
    private boolean descriptionSet;

    private Builder(UUID customerId, String receiverWalletNumber, Money amount) {
      this.customerId = Objects.requireNonNull(customerId, "customerId");
      this.receiverWalletNumber =
          Objects.requireNonNull(receiverWalletNumber, "receiverWalletNumber");
      this.amount = Objects.requireNonNull(amount, "amount");
    }

    /**
     * @param description the validated description, or null when the request has none
     * @throws IllegalArgumentException if set twice
     */
    public Builder description(String description) {
      if (descriptionSet) {
        throw new IllegalArgumentException("fingerprint description set twice");
      }
      this.description = description;
      this.descriptionSet = true;
      return this;
    }

    public TransferRequestFingerprint build() {
      String form =
          CanonicalForm.version(VERSION_LINE)
              .fixed("operation", OPERATION)
              .fixed("customer", customerId.toString())
              .text("receiverWalletNumber", receiverWalletNumber)
              .fixed("amount", amount.amount().toPlainString())
              .fixed("currency", amount.currency().getCurrencyCode())
              .text("description", description)
              .build();
      return new TransferRequestFingerprint(customerId, form);
    }
  }
}
