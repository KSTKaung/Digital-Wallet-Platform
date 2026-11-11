package com.portfolio.wallet.transfer.application;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.transfer.persistence.TransferRepository;
import com.portfolio.wallet.transfer.persistence.TransferRow;
import com.portfolio.wallet.wallet.application.TransferCounterparties;
import com.portfolio.wallet.wallet.application.WalletLookupView;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The customer's transfer reads: {@code GET /transfers}, {@code GET /transfers/{id}} and {@code GET
 * /transfers/{id}/status} (documentations/02 §1.5, §2.4; doc 06 MVP-ADR-19, -20, -24 amendment
 * "Visibility (Q8)"; doc 08 §4C.2 Q8, §4C.4 criteria 13, 20).
 *
 * <p><b>One visibility rule for all three (Q8):</b> every read goes through the repository's {@code
 * VISIBLE_TO_CALLER} predicate: the sender sees every status, the receiver only {@code COMPLETED},
 * and anything else, a missing id included, is the identical {@code 404} ({@link
 * ApiException#notFound()}). No endpoint has a rule of its own. The counterparty is the masked name
 * and masked wallet number (MVP-ADR-20). Responses never carry the description, a score, a rule or
 * a reviewer's reason; a rejection shows only the customer category (Q10).
 *
 * <p>Read-only; no lock is taken. The list reads its count and its page in one read-only {@code
 * REPEATABLE READ} transaction, so {@code totalElements} matches the items; the masked
 * counterparties are read after it.
 */
@Service
public class TransferQueryService {

  /** doc 02 §1.5: size 1–100. */
  public static final int MAX_PAGE_SIZE = 100;

  static final String INVALID_PAGE = "page: must be at least 0";
  static final String INVALID_SIZE = "size: must be between 1 and 100";

  private final TransferRepository transfers;
  private final TransferCounterparties counterparties;
  private final TransactionTemplate snapshot;

  public TransferQueryService(
      TransferRepository transfers,
      TransferCounterparties counterparties,
      PlatformTransactionManager transactionManager) {
    this.transfers = Objects.requireNonNull(transfers, "transfers");
    this.counterparties = Objects.requireNonNull(counterparties, "counterparties");
    // M3: the count and the page read one snapshot, so totalElements matches the items.
    this.snapshot = new TransactionTemplate(transactionManager);
    this.snapshot.setReadOnly(true);
    this.snapshot.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  /**
   * The caller's transfers, newest first.
   *
   * @param page from 0
   * @param size 1–100
   * @throws ApiException {@code VALIDATION_ERROR} for a page or size out of range
   */
  public TransferPage list(UUID caller, int page, int size) {
    Objects.requireNonNull(caller, "caller");
    if (page < 0) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, INVALID_PAGE);
    }
    if (size < 1 || size > MAX_PAGE_SIZE) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, INVALID_SIZE);
    }
    Snapshot read =
        Objects.requireNonNull(
            snapshot.execute(
                status ->
                    new Snapshot(
                        transfers.countVisible(caller),
                        transfers.pageVisible(caller, size, (long) page * size))));
    long total = read.total();
    List<TransferRow> rows = read.rows();
    long totalPages = (total + size - 1) / size;
    return new TransferPage(views(caller, rows), page, size, total, totalPages);
  }

  /**
   * @throws ApiException the identical {@code NOT_FOUND} unless Q8 lets the caller see it
   */
  public TransferView get(UUID caller, UUID transferId) {
    return views(caller, List.of(visible(caller, transferId))).getFirst();
  }

  /**
   * @throws ApiException the identical {@code NOT_FOUND} unless Q8 lets the caller see it
   */
  public TransferStatusView status(UUID caller, UUID transferId) {
    TransferRow row = visible(caller, transferId);
    return new TransferStatusView(row.status(), row.ledgerStatus());
  }

  private TransferRow visible(UUID caller, UUID transferId) {
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(transferId, "transferId");
    return transfers.findVisible(caller, transferId).orElseThrow(ApiException::notFound);
  }

  private List<TransferView> views(UUID caller, List<TransferRow> rows) {
    Set<UUID> wallets = new LinkedHashSet<>();
    rows.forEach(row -> wallets.add(TransferViews.counterpartyWallet(row, caller)));
    Map<UUID, WalletLookupView> parties = counterparties.of(wallets);
    return rows.stream()
        .map(
            row -> {
              WalletLookupView party = parties.get(TransferViews.counterpartyWallet(row, caller));
              return TransferViews.view(
                  row,
                  caller,
                  new CounterpartyView(party.maskedName(), party.maskedWalletNumber()));
            })
        .toList();
  }

  /**
   * The count and the page of one snapshot; a class, not a record, so no public accessor returns a
   * persistence type (ArchUnit {@code applicationApisHidePersistence}).
   */
  private static final class Snapshot {

    private final long total;
    private final List<TransferRow> rows;

    Snapshot(long total, List<TransferRow> rows) {
      this.total = total;
      this.rows = rows;
    }

    long total() {
      return total;
    }

    List<TransferRow> rows() {
      return rows;
    }
  }
}
