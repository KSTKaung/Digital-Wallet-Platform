package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.wallet.persistence.WalletRow;
import java.util.Objects;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The two wallets of a transfer, locked {@code FOR UPDATE} in ascending id order by {@link
 * WalletTransferService#lock} inside the caller's money transaction (doc 07 §8 lock order step 3).
 *
 * <p>Only the wallet module creates one, from the rows it locked, so the balances and versions it
 * carries are what the database holds under the lock; the caller reads them through {@link
 * #sender()} and {@link #recipient()}.
 *
 * <p>It serves <b>one</b> money operation, and only <b>in the transaction that locked the rows</b>:
 * the operation writes the wallets with the versions read here, under locks that end with that
 * transaction. The instance registers a transaction synchronization when it is created; it is
 * refused once that transaction has completed, inside another (for example a {@code REQUIRES_NEW}
 * that suspended it), or for a second operation. Not thread-safe: one instance per database
 * transaction.
 */
public final class LockedTransferWallets {

  private final WalletRow sender;
  private final WalletRow recipient;
  private final Owner owner = new Owner();
  private boolean used;

  /**
   * @throws IllegalStateException without an active transaction synchronization
   */
  LockedTransferWallets(WalletRow sender, WalletRow recipient) {
    this.sender = Objects.requireNonNull(sender, "sender");
    this.recipient = Objects.requireNonNull(recipient, "recipient");
    TransactionSynchronizationManager.registerSynchronization(owner);
  }

  public TransferWalletView sender() {
    return view(sender);
  }

  public TransferWalletView recipient() {
    return view(recipient);
  }

  WalletRow senderRow() {
    return sender;
  }

  WalletRow recipientRow() {
    return recipient;
  }

  /**
   * Marks the instance as used by a money operation.
   *
   * @throws IllegalStateException if an operation already used it, or the current transaction is
   *     not the one that locked the rows
   */
  void claim() {
    if (owner.completed || !inOwningTransaction()) {
      throw new IllegalStateException(
          "these wallets were locked in another transaction; lock them again in this one");
    }
    if (used) {
      throw new IllegalStateException("these locked wallets were already used; lock them again");
    }
    used = true;
  }

  private boolean inOwningTransaction() {
    return TransactionSynchronizationManager.isSynchronizationActive()
        && TransactionSynchronizationManager.getSynchronizations().contains(owner);
  }

  static TransferWalletView view(WalletRow row) {
    return new TransferWalletView(
        row.id(),
        row.customerId(),
        row.status(),
        row.tier(),
        row.availableBalance(),
        row.reservedBalance(),
        row.version(),
        row.createdAt());
  }

  /** Ids and balances stay out of logs. */
  @Override
  public String toString() {
    return "LockedTransferWallets[sender="
        + sender.status()
        + ", recipient="
        + recipient.status()
        + "]";
  }

  /** Belongs to the locking transaction; marks the instance unusable when it completes. */
  private static final class Owner implements TransactionSynchronization {

    private volatile boolean completed;

    @Override
    public void afterCompletion(int status) {
      completed = true;
    }
  }
}
