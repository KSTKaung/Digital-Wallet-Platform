package com.portfolio.wallet.ledger.application;

import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.ledger.domain.AccountMovement;
import com.portfolio.wallet.ledger.domain.EntryType;
import com.portfolio.wallet.ledger.domain.TransactionType;
import com.portfolio.wallet.ledger.persistence.LedgerHistoryRepository;
import com.portfolio.wallet.ledger.persistence.LedgerHistoryRow;
import java.util.Currency;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Pages over a wallet's customer account: the source of {@code GET /transactions}
 * (documentations/06 MVP-ADR-11 amendment; doc 07 D06; doc 08 §4C.3 "paging of a customer account's
 * transactions").
 *
 * <p>Every entry is a completed movement: entries exist only in committed postings, and held,
 * rejected, cancelled, expired and blocked transfers post nothing (MVP-ADR-24 "History"). Each
 * entry of the account is one item; the MVP postings put at most one entry per transaction on a
 * customer account (a transfer to oneself is refused).
 *
 * <p>Read-only; no lock is taken. The account id, the count and the page are read in one read-only
 * {@code REPEATABLE READ} transaction, so {@code totalElements} matches the items (as {@code GET
 * /transfers}).
 */
@Service
public class LedgerHistoryService {

  private final LedgerHistoryRepository history;
  private final TransactionTemplate snapshot;

  public LedgerHistoryService(
      LedgerHistoryRepository history, PlatformTransactionManager transactionManager) {
    this.history = Objects.requireNonNull(history, "history");
    this.snapshot = new TransactionTemplate(transactionManager);
    this.snapshot.setReadOnly(true);
    this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  /**
   * The wallet's customer-account entries, newest first, then by entry id.
   *
   * @param walletId the wallet whose account is read; the caller has established that it is the
   *     customer's own
   * @param page from 0
   * @param size at least 1; the caller enforces the API's upper bound
   * @throws IllegalArgumentException for a negative page or a size below 1
   * @throws IllegalStateException if the wallet has no customer account (they are created together,
   *     MVP-ADR-10), or an entry is not one of the MVP postings
   */
  public LedgerHistoryPage customerHistory(UUID walletId, int page, int size) {
    Objects.requireNonNull(walletId, "walletId");
    if (page < 0) {
      throw new IllegalArgumentException("page must be at least 0");
    }
    if (size < 1) {
      throw new IllegalArgumentException("size must be at least 1");
    }
    Snapshot read =
        Objects.requireNonNull(
            snapshot.execute(
                status -> {
                  UUID account =
                      history
                          .findCustomerAccountId(walletId)
                          .orElseThrow(
                              () -> new IllegalStateException("wallet without a ledger account"));
                  return new Snapshot(
                      history.countEntries(account),
                      history.pageEntries(account, size, (long) page * size));
                }));
    long total = read.total();
    long totalPages = (total + size - 1) / size;
    List<LedgerHistoryItem> items = read.rows().stream().map(LedgerHistoryService::item).toList();
    return new LedgerHistoryPage(items, page, size, total, totalPages);
  }

  static LedgerHistoryItem item(LedgerHistoryRow row) {
    AccountMovement movement;
    try {
      movement =
          AccountMovement.of(
              TransactionType.valueOf(row.transactionType()), EntryType.valueOf(row.entryType()));
    } catch (IllegalArgumentException e) {
      // V2's checks and the posting templates make this unreachable; never show such an entry.
      throw new IllegalStateException("ledger entry is not an MVP posting", e);
    }
    // Money.of: scale 2 and THB, without rounding (MVP-ADR-21).
    Money amount = Money.of(row.amount(), Currency.getInstance(row.currency()));
    return new LedgerHistoryItem(
        row.reference(),
        movement.name(),
        amount.amount(),
        amount.currency().getCurrencyCode(),
        row.createdAt());
  }

  /**
   * The count and the page of one snapshot; a class, not a record, so no public accessor returns a
   * persistence type (ArchUnit {@code applicationApisHidePersistence}).
   */
  private static final class Snapshot {

    private final long total;
    private final List<LedgerHistoryRow> rows;

    Snapshot(long total, List<LedgerHistoryRow> rows) {
      this.total = total;
      this.rows = rows;
    }

    long total() {
      return total;
    }

    List<LedgerHistoryRow> rows() {
      return rows;
    }
  }
}
