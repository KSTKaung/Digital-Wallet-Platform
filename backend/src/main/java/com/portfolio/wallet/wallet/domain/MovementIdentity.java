package com.portfolio.wallet.wallet.domain;

import com.portfolio.wallet.common.money.Money;
import java.util.Objects;
import java.util.UUID;

/**
 * What identifies a deposit or withdrawal when its idempotency record is gone (documentations/08
 * §4B.2 Q14, "derived, confirm"). The business rows keep {@code UNIQUE (customer_id,
 * idempotency_key)} after the record is purged (doc 06 MVP-ADR-02 amendment). A retry with that key
 * gets the stored movement back only if customer, operation, wallet, amount and currency all match;
 * anything else is {@code 409 IDEMPOTENCY_KEY_REUSED}. Other request fields, such as the deposit's
 * free-text reference, are not compared: the owner's rule names these five.
 *
 * @param amount amount and currency; {@link Money} always has scale 2, so equal amounts compare
 *     equal whatever their input format
 */
public record MovementIdentity(UUID customerId, Operation operation, UUID walletId, Money amount) {

  public MovementIdentity {
    Objects.requireNonNull(customerId, "customerId");
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(walletId, "walletId");
    Objects.requireNonNull(amount, "amount");
  }

  /** Q14: true only if customer, operation, wallet, amount and currency are all the same. */
  public boolean isSameMovementAs(MovementIdentity stored) {
    Objects.requireNonNull(stored, "stored");
    return customerId.equals(stored.customerId)
        && operation == stored.operation
        && walletId.equals(stored.walletId)
        && amount.equals(stored.amount);
  }
}
