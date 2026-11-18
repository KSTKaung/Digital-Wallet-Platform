package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.wallet.persistence.WalletReconciliationRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the scheduled reconciliation reads from the wallet module (documentations/06 MVP-ADR-03
 * checks 1–4, MVP-ADR-22 Y5; doc 07 §5, D18). Read-only, no lock.
 *
 * <p>Every method joins the caller's transaction ({@link Propagation#MANDATORY}), so the snapshots
 * are read at the same instant as the ledger and the transfers they are compared with.
 */
@Service
public class WalletReconciliationReads {

  private final WalletReconciliationRepository repository;

  public WalletReconciliationReads(WalletReconciliationRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  /** A wallet's balance snapshot. */
  public record WalletBalances(UUID walletId, BigDecimal available, BigDecimal reserved) {

    public WalletBalances {
      Objects.requireNonNull(walletId, "walletId");
      Objects.requireNonNull(available, "available");
      Objects.requireNonNull(reserved, "reserved");
    }
  }

  /**
   * A completed deposit or withdrawal of {@code walletId}; {@code reference} is its ledger
   * transaction reference.
   */
  public record CompletedMovement(UUID id, UUID walletId, String reference, BigDecimal amount) {

    public CompletedMovement {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(walletId, "walletId");
      Objects.requireNonNull(reference, "reference");
      Objects.requireNonNull(amount, "amount");
    }
  }

  /**
   * A non-zero reservation on a daily-limit usage row.
   *
   * @param operation {@code TRANSFER}, {@code DEPOSIT} or {@code WITHDRAWAL}
   * @param limitDate the row's stored Asia/Bangkok day
   */
  public record ReservedUsage(
      UUID walletId, String operation, LocalDate limitDate, BigDecimal reserved) {

    public ReservedUsage {
      Objects.requireNonNull(walletId, "walletId");
      Objects.requireNonNull(operation, "operation");
      Objects.requireNonNull(limitDate, "limitDate");
      Objects.requireNonNull(reserved, "reserved");
    }
  }

  /** Every wallet's available and reserved balance. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public List<WalletBalances> balances() {
    return repository.balances().stream()
        .map(row -> new WalletBalances(row.walletId(), row.available(), row.reserved()))
        .toList();
  }

  /** Every completed deposit. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public List<CompletedMovement> completedDeposits() {
    return repository.completedDeposits().stream()
        .map(row -> new CompletedMovement(row.id(), row.walletId(), row.reference(), row.amount()))
        .toList();
  }

  /** Every completed withdrawal. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public List<CompletedMovement> completedWithdrawals() {
    return repository.completedWithdrawals().stream()
        .map(row -> new CompletedMovement(row.id(), row.walletId(), row.reference(), row.amount()))
        .toList();
  }

  /** Every usage row whose reservation is not zero, of any operation. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public List<ReservedUsage> reservedUsage() {
    return repository.reservedUsage().stream()
        .map(
            row ->
                new ReservedUsage(row.walletId(), row.operation(), row.limitDate(), row.reserved()))
        .toList();
  }
}
