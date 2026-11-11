package com.portfolio.wallet.transfer.domain;

import com.portfolio.wallet.common.money.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * What identifies a transfer when its idempotency record is gone: the rule of deposits and
 * withdrawals (documentations/08 §4B.2 Q14) applied to {@code POST /transfers} (owner, 2026-11-11,
 * option a). The transfer row keeps {@code UNIQUE (sender_customer_id, idempotency_key)} (V4) after
 * the record expires or is purged. A retry with that key gets the transfer's response back only if
 * the sender, the operation, the sender's wallet, the recipient's wallet, the amount and the
 * currency all match; anything else is {@code 409 IDEMPOTENCY_KEY_REUSED}.
 *
 * <p>The operation is always {@code TRANSFER}: only transfers are stored in {@code
 * transfer.transfers}, so a row found there by sender and key is a transfer. The description is not
 * compared, as the deposit's note is not (doc 08 Q14, Z3): the rule names these fields only.
 *
 * @param amount amount and currency; {@link Money} always has scale 2, so equal amounts compare
 *     equal whatever their input format
 */
public record TransferIdentity(
    UUID senderCustomerId, UUID senderWalletId, UUID receiverWalletId, Money amount) {

  public TransferIdentity {
    Objects.requireNonNull(senderCustomerId, "senderCustomerId");
    Objects.requireNonNull(senderWalletId, "senderWalletId");
    Objects.requireNonNull(receiverWalletId, "receiverWalletId");
    Objects.requireNonNull(amount, "amount");
  }

  /** True only if sender, sender wallet, recipient wallet, amount and currency are all the same. */
  public boolean isSameTransferAs(TransferIdentity stored) {
    Objects.requireNonNull(stored, "stored");
    return senderCustomerId.equals(stored.senderCustomerId)
        && senderWalletId.equals(stored.senderWalletId)
        && receiverWalletId.equals(stored.receiverWalletId)
        && amount.equals(stored.amount);
  }

  /** Prints neither the parties nor the amount. */
  @Override
  public String toString() {
    return "TransferIdentity[]";
  }
}
