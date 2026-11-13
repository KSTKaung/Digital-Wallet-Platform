package com.portfolio.wallet.wallet.application;

import java.util.Objects;
import java.util.UUID;

/**
 * The recipient of {@code POST /transfers}, resolved by {@link
 * WalletLookupService#resolveTransferRecipient} (documentations/08 §4C.2 Q3 step 3, Q5): an {@code
 * ACTIVE} wallet other than the sender's, as committed when it was resolved. The money transaction
 * locks it and checks it again (doc 08 §5, step 4 notes).
 *
 * @param walletId the wallet to lock and credit
 * @param customerId its owner, the transfer's receiver
 * @param maskedName the first name plus the last name's initial (MVP-ADR-20)
 * @param maskedWalletNumber only the last 4 digits visible (MVP-ADR-20)
 */
public record TransferRecipient(
    UUID walletId, UUID customerId, String maskedName, String maskedWalletNumber) {

  public TransferRecipient {
    Objects.requireNonNull(walletId, "walletId");
    Objects.requireNonNull(customerId, "customerId");
    Objects.requireNonNull(maskedName, "maskedName");
    Objects.requireNonNull(maskedWalletNumber, "maskedWalletNumber");
  }

  /** Ids and the masked values stay out of logs. */
  @Override
  public String toString() {
    return "TransferRecipient[]";
  }
}
