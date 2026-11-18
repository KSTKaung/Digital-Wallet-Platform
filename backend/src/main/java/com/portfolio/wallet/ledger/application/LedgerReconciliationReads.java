package com.portfolio.wallet.ledger.application;

import com.portfolio.wallet.ledger.domain.AccountType;
import com.portfolio.wallet.ledger.domain.LedgerAccount;
import com.portfolio.wallet.ledger.persistence.LedgerReconciliationRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the scheduled reconciliation reads from the ledger (documentations/06 MVP-ADR-03 checks 1
 * and 2, Σ DEBIT = Σ CREDIT; doc 07 §5, D18). Read-only, no lock.
 *
 * <p>Every method joins the caller's transaction ({@link Propagation#MANDATORY}): the
 * reconciliation reads every module in one read-only {@code REPEATABLE READ} transaction, so the
 * ledger is read at the same instant as the wallet snapshots and business records it is compared
 * with. Each call outside a transaction is refused rather than read from a different instant.
 */
@Service
public class LedgerReconciliationReads {

  private final LedgerReconciliationRepository repository;

  public LedgerReconciliationReads(LedgerReconciliationRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  /**
   * An account's totals.
   *
   * @param walletId the wallet of a {@code CUSTOMER} account; {@code null} for system and suspense
   *     accounts
   * @param cashClearing whether this is {@code SYS-CASH-CLEARING}, the system side of every deposit
   *     and withdrawal (doc 01 §6.4 posting templates)
   */
  public record AccountTotals(
      UUID accountId, UUID walletId, BigDecimal debits, BigDecimal credits, boolean cashClearing) {

    public AccountTotals {
      Objects.requireNonNull(accountId, "accountId");
      Objects.requireNonNull(debits, "debits");
      Objects.requireNonNull(credits, "credits");
      if (cashClearing && walletId != null) {
        throw new IllegalArgumentException("a customer account is not the cash clearing account");
      }
    }

    /** Any account but {@code SYS-CASH-CLEARING}. */
    public AccountTotals(UUID accountId, UUID walletId, BigDecimal debits, BigDecimal credits) {
      this(accountId, walletId, debits, credits, false);
    }

    /** Σ CREDIT − Σ DEBIT (doc 01 §6.2, the customer sign convention). */
    public BigDecimal balance() {
      return credits.subtract(debits);
    }
  }

  /**
   * A ledger transaction: id, the business record's reference, its type, and its entry totals.
   *
   * @param entries how many entries it has
   * @param debitAccounts the distinct accounts it debits
   * @param creditAccounts the distinct accounts it credits
   */
  public record Posting(
      UUID id,
      String reference,
      String type,
      long entries,
      BigDecimal debits,
      BigDecimal credits,
      Set<UUID> debitAccounts,
      Set<UUID> creditAccounts) {

    public Posting {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(reference, "reference");
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(debits, "debits");
      Objects.requireNonNull(credits, "credits");
      debitAccounts = Set.copyOf(debitAccounts);
      creditAccounts = Set.copyOf(creditAccounts);
    }

    /**
     * At least two entries and Σ DEBIT = Σ CREDIT (V2's deferred trigger refuses anything else at
     * commit, so only a bypass can break it). Compared scale-insensitively.
     */
    public boolean balanced() {
      return entries >= 2 && debits.compareTo(credits) == 0;
    }
  }

  /** Every ledger account with its entry totals. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public List<AccountTotals> accountTotals() {
    return repository.accountTotals().stream()
        .map(
            row ->
                new AccountTotals(
                    row.accountId(),
                    AccountType.CUSTOMER.name().equals(row.accountType())
                        ? row.ownerReference()
                        : null,
                    row.debits(),
                    row.credits(),
                    AccountType.SYSTEM.name().equals(row.accountType())
                        && LedgerAccount.CASH_CLEARING_CODE.equals(row.code())))
        .toList();
  }

  /** Every ledger transaction with its entry totals. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public List<Posting> postings() {
    return repository.postings().stream()
        .map(
            row ->
                new Posting(
                    row.id(),
                    row.reference(),
                    row.type(),
                    row.entries(),
                    row.debits(),
                    row.credits(),
                    Set.copyOf(row.debitAccounts()),
                    Set.copyOf(row.creditAccounts())))
        .toList();
  }
}
