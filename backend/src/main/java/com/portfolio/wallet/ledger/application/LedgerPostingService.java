package com.portfolio.wallet.ledger.application;

import com.portfolio.wallet.common.correlation.CorrelationId;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.ledger.domain.AccountType;
import com.portfolio.wallet.ledger.domain.FinancialTransaction;
import com.portfolio.wallet.ledger.domain.LedgerAccount;
import com.portfolio.wallet.ledger.domain.LedgerAccountStatus;
import com.portfolio.wallet.ledger.domain.LedgerEntry;
import com.portfolio.wallet.ledger.domain.PostingTemplate;
import com.portfolio.wallet.ledger.persistence.LedgerAccountRow;
import com.portfolio.wallet.ledger.persistence.LedgerPostingRepository;
import java.util.Currency;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Posts the balanced double-entry transaction of a deposit or withdrawal (documentations/01 §6.4;
 * doc 06 MVP-ADR-03, -13). Only inside the caller's money transaction, so the posting commits or
 * rolls back with the wallet snapshot, the business record and the audit row. The caller has locked
 * the wallet first; the entry trigger then locks the customer account {@code FOR SHARE} (doc 07 §8
 * lock order), and the deferred trigger checks at commit that Σ DEBIT = Σ CREDIT.
 *
 * <p>Both accounts must be {@code ACTIVE} ({@link PostingTemplate}). A frozen wallet is refused by
 * the caller before this point; an account that is not {@code ACTIVE} here means it drifted from
 * its wallet and fails the posting.
 */
@Service
public class LedgerPostingService {

  private final LedgerPostingRepository postings;

  public LedgerPostingService(LedgerPostingRepository postings) {
    this.postings = postings;
  }

  /**
   * DR {@code SYS-CASH-CLEARING} / CR the wallet's customer account.
   *
   * @param reference the business record's unique reference, also the ledger {@code
   *     transaction_reference}
   * @return the financial transaction id
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID postDeposit(UUID walletId, String reference, Money amount) {
    return post(walletId, reference, amount, PostingTemplate::deposit);
  }

  /** DR the wallet's customer account / CR {@code SYS-CASH-CLEARING}; otherwise as a deposit. */
  @Transactional(propagation = Propagation.MANDATORY)
  public UUID postWithdrawal(UUID walletId, String reference, Money amount) {
    return post(walletId, reference, amount, PostingTemplate::withdrawal);
  }

  private UUID post(UUID walletId, String reference, Money amount, Template template) {
    Objects.requireNonNull(walletId, "walletId");
    LedgerAccount customer =
        toDomain(
            postings
                .findCustomerAccount(walletId)
                .orElseThrow(() -> new IllegalStateException("wallet without a ledger account")));
    LedgerAccount cashClearing =
        toDomain(
            postings
                .findByCode(LedgerAccount.CASH_CLEARING_CODE)
                .orElseThrow(() -> new IllegalStateException("cash clearing account missing")));
    FinancialTransaction transaction = template.build(reference, customer, cashClearing, amount);
    UUID id = UUID.randomUUID();
    postings.insertTransaction(
        id,
        transaction.reference(),
        transaction.type().name(),
        transaction.currency().getCurrencyCode(),
        CorrelationId.current().orElse(null));
    for (LedgerEntry entry : transaction.entries()) {
      postings.insertEntry(
          id,
          entry.accountId(),
          entry.type().name(),
          entry.amount().amount(),
          entry.amount().currency().getCurrencyCode());
    }
    return id;
  }

  private static LedgerAccount toDomain(LedgerAccountRow row) {
    return LedgerAccount.restore(
        row.id(),
        row.code(),
        AccountType.valueOf(row.accountType()),
        row.ownerReference(),
        Currency.getInstance(row.currency()),
        LedgerAccountStatus.valueOf(row.status()));
  }

  @FunctionalInterface
  private interface Template {
    FinancialTransaction build(
        String reference, LedgerAccount customer, LedgerAccount cashClearing, Money amount);
  }
}
