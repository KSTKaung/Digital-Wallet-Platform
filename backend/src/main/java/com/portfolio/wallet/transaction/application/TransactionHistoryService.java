package com.portfolio.wallet.transaction.application;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.ledger.application.LedgerHistoryItem;
import com.portfolio.wallet.ledger.application.LedgerHistoryPage;
import com.portfolio.wallet.ledger.application.LedgerHistoryService;
import com.portfolio.wallet.wallet.application.WalletQueryService;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * The customer's history, {@code GET /transactions} (documentations/02 §1.5, §2.4 "MVP history";
 * doc 06 MVP-ADR-11 amendment; doc 07 D06; doc 08 §4C.2 Q14, §4C.4 criterion 17).
 *
 * <p>Read from the ledger through {@link LedgerHistoryService}, over the caller's own customer
 * account: the caller is the JWT subject, its wallet is resolved by the wallet module, and no
 * request parameter can name another account, so another customer's entries cannot be reached.
 * Completed movements only: held, rejected, cancelled, expired and blocked transfers post nothing
 * and are listed by {@code GET /transfers} (MVP-ADR-24 "History").
 *
 * <p><b>Decided by the owner (2026-11-14, PR 5b step 11):</b>
 *
 * <ul>
 *   <li>before KYC approval the caller has no wallet and therefore no ledger account; the history
 *       is then {@code 200} with an empty page, as {@code GET /transfers} answers;
 *   <li>a frozen wallet's history is served unchanged: freezing refuses movements, not reads;
 *   <li>{@code postedAt} is the posting transaction's start time (the entry's database {@code
 *       now()}), a few milliseconds before its commit;
 *   <li>items carry no {@code limit_date};
 *   <li>an entry that no MVP posting produces makes the request fail with {@code 500} rather than
 *       be shown ({@link LedgerHistoryService}).
 * </ul>
 *
 * <p>Read-only; no lock is taken, and no D14 timeouts, like the other customer reads. End-to-end
 * tests: {@code transfer.web.TransactionHistoryIT}.
 */
@Service
public class TransactionHistoryService {

  /** doc 02 §1.5: size 1–100. */
  public static final int MAX_PAGE_SIZE = 100;

  /** Every item is a committed posting (MVP-ADR-03). */
  static final String POSTED = "POSTED";

  static final String INVALID_PAGE = "page: must be at least 0";
  static final String INVALID_SIZE = "size: must be between 1 and 100";

  private final WalletQueryService wallets;
  private final LedgerHistoryService ledger;

  public TransactionHistoryService(WalletQueryService wallets, LedgerHistoryService ledger) {
    this.wallets = Objects.requireNonNull(wallets, "wallets");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
  }

  /**
   * The caller's completed movements, newest first.
   *
   * @param page from 0
   * @param size 1–100
   * @throws ApiException {@code VALIDATION_ERROR} for a page or size out of range
   */
  public TransactionPage list(UUID caller, int page, int size) {
    Objects.requireNonNull(caller, "caller");
    if (page < 0) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, INVALID_PAGE);
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, INVALID_SIZE);
    }
    Optional<UUID> wallet = wallets.findWalletId(caller);
    if (wallet.isEmpty()) {
      return new TransactionPage(List.of(), page, size, 0, 0);
    }
    LedgerHistoryPage read = ledger.customerHistory(wallet.get(), page, size);
    return new TransactionPage(
        read.items().stream().map(TransactionHistoryService::view).toList(),
        read.page(),
        read.size(),
        read.totalElements(),
        read.totalPages());
  }

  private static TransactionView view(LedgerHistoryItem item) {
    return new TransactionView(
        item.reference(), item.movement(), item.amount(), item.currency(), item.postedAt(), POSTED);
  }
}
