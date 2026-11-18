package com.portfolio.wallet.admin.application;

import com.portfolio.wallet.admin.application.ReconciliationMismatch.Kind;
import com.portfolio.wallet.ledger.application.LedgerReconciliationReads.AccountTotals;
import com.portfolio.wallet.ledger.application.LedgerReconciliationReads.Posting;
import com.portfolio.wallet.risk.application.RiskReconciliationReads.ReviewOutcome;
import com.portfolio.wallet.transfer.application.TransferReconciliationReads.CompletedTransfer;
import com.portfolio.wallet.transfer.application.TransferReconciliationReads.HeldSum;
import com.portfolio.wallet.transfer.application.TransferReconciliationReads.HeldTransferOutcome;
import com.portfolio.wallet.wallet.application.WalletReconciliationReads.CompletedMovement;
import com.portfolio.wallet.wallet.application.WalletReconciliationReads.ReservedUsage;
import com.portfolio.wallet.wallet.application.WalletReconciliationReads.WalletBalances;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The comparisons of the reconciliation (documentations/06 MVP-ADR-03 checks 1–5 and Σ DEBIT = Σ
 * CREDIT), in memory, over one {@link Snapshot}. Pure: no I/O, no logging, no clock.
 */
final class Reconciler {

  static final String TRANSFER_OPERATION = "TRANSFER";

  private Reconciler() {}

  /** Everything the checks compare, read at one instant (see {@link ReconciliationService}). */
  record Snapshot(
      List<WalletBalances> wallets,
      List<CompletedMovement> deposits,
      List<CompletedMovement> withdrawals,
      List<ReservedUsage> reservedUsage,
      List<CompletedTransfer> completedTransfers,
      List<HeldSum> heldSums,
      List<HeldTransferOutcome> everHeld,
      List<ReviewOutcome> reviews,
      List<AccountTotals> accounts,
      List<Posting> postings) {

    Snapshot {
      wallets = List.copyOf(wallets);
      deposits = List.copyOf(deposits);
      withdrawals = List.copyOf(withdrawals);
      reservedUsage = List.copyOf(reservedUsage);
      completedTransfers = List.copyOf(completedTransfers);
      heldSums = List.copyOf(heldSums);
      everHeld = List.copyOf(everHeld);
      reviews = List.copyOf(reviews);
      accounts = List.copyOf(accounts);
      postings = List.copyOf(postings);
    }

    /** Rows per read, for the run summary. */
    Map<String, Integer> rowsRead() {
      Map<String, Integer> rows = new LinkedHashMap<>();
      rows.put("wallets", wallets.size());
      rows.put("deposits", deposits.size());
      rows.put("withdrawals", withdrawals.size());
      rows.put("reservedUsageRows", reservedUsage.size());
      rows.put("completedTransfers", completedTransfers.size());
      rows.put("heldSums", heldSums.size());
      rows.put("everHeldTransfers", everHeld.size());
      rows.put("reviews", reviews.size());
      rows.put("ledgerAccounts", accounts.size());
      rows.put("ledgerTransactions", postings.size());
      return rows;
    }
  }

  /** Every mismatch of the snapshot, check by check. */
  static List<ReconciliationMismatch> compare(Snapshot snapshot) {
    Objects.requireNonNull(snapshot, "snapshot");
    List<ReconciliationMismatch> found = new ArrayList<>();
    walletBalances(snapshot, found);
    completedMovements(snapshot, found);
    reservedBalances(snapshot, found);
    reservedUsage(snapshot, found);
    reviewOutcomes(snapshot, found);
    ledgerSums(snapshot, found);
    return found;
  }

  // Check 1 -------------------------------------------------------------------------------------

  /**
   * Each wallet's available + reserved equals Σ CREDIT − Σ DEBIT of its customer account. A wallet
   * has one customer account ({@code uq_ledger_accounts_customer_owner}); a second one is reported,
   * never dropped, and the first is compared with the wallet.
   */
  private static void walletBalances(Snapshot snapshot, List<ReconciliationMismatch> found) {
    Map<UUID, AccountTotals> byWallet = new HashMap<>();
    for (AccountTotals account : snapshot.accounts()) {
      if (account.walletId() != null) {
        AccountTotals first = byWallet.putIfAbsent(account.walletId(), account);
        if (first != null) {
          found.add(
              mismatch(
                  Kind.DUPLICATE_CUSTOMER_ACCOUNT,
                  "walletId",
                  account.walletId(),
                  "ledgerAccountId",
                  first.accountId(),
                  "otherLedgerAccountId",
                  account.accountId()));
        }
      }
    }
    Set<UUID> wallets = new HashSet<>();
    for (WalletBalances wallet : snapshot.wallets()) {
      wallets.add(wallet.walletId());
      AccountTotals account = byWallet.get(wallet.walletId());
      if (account == null) {
        found.add(mismatch(Kind.WALLET_WITHOUT_LEDGER_ACCOUNT, "walletId", wallet.walletId()));
      } else if (wallet.available().add(wallet.reserved()).compareTo(account.balance()) != 0) {
        found.add(
            mismatch(
                Kind.WALLET_BALANCE_DIFFERS,
                "walletId",
                wallet.walletId(),
                "ledgerAccountId",
                account.accountId()));
      }
    }
    for (AccountTotals account : snapshot.accounts()) {
      if (account.walletId() != null && !wallets.contains(account.walletId())) {
        found.add(
            mismatch(
                Kind.LEDGER_ACCOUNT_WITHOUT_WALLET,
                "ledgerAccountId",
                account.accountId(),
                "walletId",
                account.walletId()));
      }
    }
  }

  // Check 2 -------------------------------------------------------------------------------------

  /**
   * Each completed deposit, withdrawal and transfer has exactly one ledger transaction: the one
   * whose {@code transaction_reference} is the record's reference ({@code uq_..._reference} on both
   * sides keeps it to at most one) and whose type is the record's. Also the other way: every ledger
   * transaction belongs to one of these records, since nothing else posts in the MVP. A matched
   * posting moves exactly the record's amount, between exactly the accounts of its posting template
   * (doc 01 §6.4), from the per-transaction entry totals of the ledger read.
   */
  private static void completedMovements(Snapshot snapshot, List<ReconciliationMismatch> found) {
    Map<String, Posting> byReference = new HashMap<>();
    for (Posting posting : snapshot.postings()) {
      byReference.put(posting.reference(), posting);
    }
    // The first account of a wallet, as check 1 compares it; a second one is reported there.
    Map<UUID, UUID> accountOfWallet = new HashMap<>();
    UUID cashClearing = null;
    for (AccountTotals account : snapshot.accounts()) {
      if (account.walletId() != null) {
        accountOfWallet.putIfAbsent(account.walletId(), account.accountId());
      }
      if (account.cashClearing() && cashClearing == null) {
        cashClearing = account.accountId();
      }
    }
    Set<UUID> claimed = new HashSet<>();
    for (CompletedMovement deposit : snapshot.deposits()) {
      Posting posting =
          movement("DEPOSIT", deposit.id(), deposit.reference(), byReference, claimed, found);
      amount("DEPOSIT", deposit.id(), deposit.amount(), posting, found);
      accounts(
          "DEPOSIT",
          deposit.id(),
          posting,
          cashClearing,
          accountOfWallet.get(deposit.walletId()),
          found);
    }
    for (CompletedMovement withdrawal : snapshot.withdrawals()) {
      Posting posting =
          movement(
              "WITHDRAWAL", withdrawal.id(), withdrawal.reference(), byReference, claimed, found);
      amount("WITHDRAWAL", withdrawal.id(), withdrawal.amount(), posting, found);
      accounts(
          "WITHDRAWAL",
          withdrawal.id(),
          posting,
          accountOfWallet.get(withdrawal.walletId()),
          cashClearing,
          found);
    }
    for (CompletedTransfer transfer : snapshot.completedTransfers()) {
      Posting posting =
          movement("TRANSFER", transfer.id(), transfer.reference(), byReference, claimed, found);
      amount("TRANSFER", transfer.id(), transfer.amount(), posting, found);
      accounts(
          "TRANSFER",
          transfer.id(),
          posting,
          accountOfWallet.get(transfer.senderWalletId()),
          accountOfWallet.get(transfer.receiverWalletId()),
          found);
    }
    for (Posting posting : snapshot.postings()) {
      if (!claimed.contains(posting.id())) {
        found.add(mismatch(Kind.POSTING_WITHOUT_MOVEMENT, "ledgerTransactionId", posting.id()));
      }
    }
  }

  /**
   * The record's posting, claimed; {@code null} (after reporting why) if it has none or one of
   * another type, so no amount or accounts are compared then.
   */
  private static Posting movement(
      String type,
      UUID id,
      String reference,
      Map<String, Posting> byReference,
      Set<UUID> claimed,
      List<ReconciliationMismatch> found) {
    Posting posting = byReference.get(reference);
    if (posting == null) {
      found.add(mismatch(Kind.MOVEMENT_NOT_POSTED, "recordType", type, "recordId", id));
      return null;
    }
    if (!type.equals(posting.type())) {
      found.add(
          mismatch(
              Kind.MOVEMENT_POSTED_AS_OTHER_TYPE,
              "recordType",
              type,
              "recordId",
              id,
              "ledgerTransactionId",
              posting.id()));
      return null;
    }
    claimed.add(posting.id());
    return posting;
  }

  /**
   * The posting moves the record's amount: Σ DEBIT and Σ CREDIT both equal it (scale-insensitive).
   * An unbalanced posting is also reported by the Σ check.
   */
  private static void amount(
      String type,
      UUID id,
      BigDecimal amount,
      Posting posting,
      List<ReconciliationMismatch> found) {
    if (posting == null) {
      return;
    }
    if (posting.debits().compareTo(amount) != 0 || posting.credits().compareTo(amount) != 0) {
      found.add(
          mismatch(
              Kind.POSTING_AMOUNT_DIFFERS,
              "recordType",
              type,
              "recordId",
              id,
              "ledgerTransactionId",
              posting.id()));
    }
  }

  /**
   * The posting debits exactly {@code debited} and credits exactly {@code credited}, the accounts
   * of the doc 01 §6.4 posting template for the record: a deposit {@code SYS-CASH-CLEARING} to its
   * wallet's customer account, a withdrawal the reverse, a transfer the sender wallet's customer
   * account to the receiver wallet's. So a deposit credits no other customer account and debits
   * none, and a withdrawal the reverse. An expected account that does not exist ({@code null}: a
   * wallet without an account, or no cash clearing account) never matches.
   */
  private static void accounts(
      String type,
      UUID id,
      Posting posting,
      UUID debited,
      UUID credited,
      List<ReconciliationMismatch> found) {
    if (posting == null) {
      return;
    }
    boolean matches =
        debited != null
            && credited != null
            && posting.debitAccounts().equals(Set.of(debited))
            && posting.creditAccounts().equals(Set.of(credited));
    if (!matches) {
      found.add(
          mismatch(
              Kind.POSTING_ACCOUNTS_DIFFER,
              "recordType",
              type,
              "recordId",
              id,
              "ledgerTransactionId",
              posting.id()));
    }
  }

  // Check 3 -------------------------------------------------------------------------------------

  /**
   * Each wallet's reserved balance equals Σ of its {@code PENDING_REVIEW} transfers, and both
   * wallets of every held transfer exist ({@code side} names which one is missing).
   */
  private static void reservedBalances(Snapshot snapshot, List<ReconciliationMismatch> found) {
    Map<UUID, BigDecimal> held = new LinkedHashMap<>();
    Set<UUID> recipients = new LinkedHashSet<>();
    for (HeldSum sum : snapshot.heldSums()) {
      held.merge(sum.senderWalletId(), sum.amount(), BigDecimal::add);
      recipients.add(sum.receiverWalletId());
    }
    Set<UUID> wallets = new HashSet<>();
    for (WalletBalances wallet : snapshot.wallets()) {
      wallets.add(wallet.walletId());
      BigDecimal expected = held.remove(wallet.walletId());
      if (wallet.reserved().compareTo(expected == null ? BigDecimal.ZERO : expected) != 0) {
        found.add(mismatch(Kind.RESERVED_BALANCE_DIFFERS, "walletId", wallet.walletId()));
      }
    }
    for (UUID unknown : held.keySet()) {
      found.add(mismatch(Kind.HOLD_OF_UNKNOWN_WALLET, "walletId", unknown, "side", "SENDER"));
    }
    for (UUID recipient : recipients) {
      if (!wallets.contains(recipient)) {
        found.add(
            mismatch(Kind.HOLD_OF_UNKNOWN_WALLET, "walletId", recipient, "side", "RECIPIENT"));
      }
    }
  }

  // Check 4 -------------------------------------------------------------------------------------

  private record UsageKey(UUID walletId, String operation, LocalDate limitDate) {}

  /**
   * Each usage row's reserved amount equals Σ of the transfers held on its stored day (MVP-ADR-22
   * Y5): for {@code TRANSFER} rows the held transfers of that wallet and {@code limit_date}, for
   * other operations zero (only transfers are held). A day whose holds have no row, or a row with
   * nothing reserved, counts as zero reserved.
   */
  private static void reservedUsage(Snapshot snapshot, List<ReconciliationMismatch> found) {
    Map<UsageKey, BigDecimal> expected = new HashMap<>();
    for (HeldSum sum : snapshot.heldSums()) {
      expected.merge(
          new UsageKey(sum.senderWalletId(), TRANSFER_OPERATION, sum.limitDate()),
          sum.amount(),
          BigDecimal::add);
    }
    Map<UsageKey, BigDecimal> actual = new HashMap<>();
    for (ReservedUsage usage : snapshot.reservedUsage()) {
      actual.merge(
          new UsageKey(usage.walletId(), usage.operation(), usage.limitDate()),
          usage.reserved(),
          BigDecimal::add);
    }
    Set<UsageKey> keys = new LinkedHashSet<>(actual.keySet());
    keys.addAll(expected.keySet());
    for (UsageKey key : keys) {
      BigDecimal want = expected.getOrDefault(key, BigDecimal.ZERO);
      BigDecimal have = actual.getOrDefault(key, BigDecimal.ZERO);
      if (want.compareTo(have) != 0) {
        found.add(
            mismatch(
                Kind.RESERVED_USAGE_DIFFERS,
                "walletId",
                key.walletId(),
                "operation",
                key.operation(),
                "limitDate",
                key.limitDate()));
      }
    }
  }

  // Check 5 -------------------------------------------------------------------------------------

  /**
   * Each review row agrees with its transfer, by outcome and category, never by time (MVP-ADR-24
   * "Review outcomes", "Staff decisions": "Times"): every transfer that left {@code PENDING_REVIEW}
   * has a review row whose outcome matches its status and category, and no other transfer has one.
   * At most one row per transfer is the database's ({@code uq_risk_reviews_transfer}); a second one
   * is still reported, never dropped, and the first is compared with the transfer.
   */
  private static void reviewOutcomes(Snapshot snapshot, List<ReconciliationMismatch> found) {
    Map<UUID, ReviewOutcome> reviews = new LinkedHashMap<>();
    for (ReviewOutcome review : snapshot.reviews()) {
      ReviewOutcome first = reviews.putIfAbsent(review.transferId(), review);
      if (first != null) {
        found.add(
            mismatch(
                Kind.DUPLICATE_REVIEW,
                "transferId",
                review.transferId(),
                "reviewId",
                first.reviewId(),
                "otherReviewId",
                review.reviewId()));
      }
    }
    for (HeldTransferOutcome transfer : snapshot.everHeld()) {
      ReviewOutcome review = reviews.remove(transfer.id());
      if ("PENDING_REVIEW".equals(transfer.status())) {
        if (review != null) {
          found.add(
              mismatch(
                  Kind.REVIEW_WITHOUT_DECIDED_TRANSFER,
                  "transferId",
                  transfer.id(),
                  "reviewId",
                  review.reviewId()));
        }
      } else if (review == null) {
        found.add(mismatch(Kind.DECIDED_WITHOUT_REVIEW, "transferId", transfer.id()));
      } else if (!agrees(review, transfer)) {
        found.add(
            mismatch(
                Kind.REVIEW_DISAGREES_WITH_TRANSFER,
                "transferId",
                transfer.id(),
                "reviewId",
                review.reviewId()));
      }
      // The review row's assessment must be the one its transfer references (the composite key
      // of V5 only ties the row to an assessment of the same transfer id).
      if (review != null && !review.assessmentId().equals(transfer.riskAssessmentId())) {
        found.add(
            mismatch(
                Kind.REVIEW_ASSESSMENT_DIFFERS,
                "transferId",
                transfer.id(),
                "reviewId",
                review.reviewId(),
                "reviewAssessmentId",
                review.assessmentId(),
                "transferAssessmentId",
                transfer.riskAssessmentId()));
      }
    }
    // Rows left: their transfer never entered review (created COMPLETED or BLOCKED) or is missing.
    for (ReviewOutcome review : reviews.values()) {
      found.add(
          mismatch(
              Kind.REVIEW_WITHOUT_DECIDED_TRANSFER,
              "transferId",
              review.transferId(),
              "reviewId",
              review.reviewId()));
    }
  }

  /**
   * The pairings of D2: a staff approval with {@code COMPLETED}; a staff rejection with {@code
   * REJECTED} and {@code REVIEW_DECLINED}; a system rejection with {@code REJECTED} and {@code
   * WALLET_UNAVAILABLE}; a cancel with {@code CANCELLED}; an expiry (by the job or by a late
   * action) with {@code EXPIRED}. Any other combination disagrees.
   */
  static boolean agrees(ReviewOutcome review, HeldTransferOutcome transfer) {
    String outcome = review.action() + "/" + review.decidedBy();
    String category = transfer.rejectionReasonCategory();
    return switch (transfer.status()) {
      case "COMPLETED" -> outcome.equals("APPROVE/STAFF") && category == null;
      case "REJECTED" ->
          (outcome.equals("REJECT/STAFF") && "REVIEW_DECLINED".equals(category))
              || (outcome.equals("REJECT/SYSTEM") && "WALLET_UNAVAILABLE".equals(category));
      case "CANCELLED" -> outcome.equals("CANCEL/CUSTOMER") && category == null;
      case "EXPIRED" -> outcome.equals("EXPIRE/SYSTEM") && category == null;
      default -> false;
    };
  }

  // Σ DEBIT = Σ CREDIT --------------------------------------------------------------------------

  /**
   * Per ledger transaction (from its entry totals: at least two entries, Σ DEBIT = Σ CREDIT) and
   * over the whole ledger.
   */
  private static void ledgerSums(Snapshot snapshot, List<ReconciliationMismatch> found) {
    for (Posting posting : snapshot.postings()) {
      if (!posting.balanced()) {
        found.add(mismatch(Kind.TRANSACTION_UNBALANCED, "ledgerTransactionId", posting.id()));
      }
    }
    BigDecimal debits = BigDecimal.ZERO;
    BigDecimal credits = BigDecimal.ZERO;
    for (AccountTotals account : snapshot.accounts()) {
      debits = debits.add(account.debits());
      credits = credits.add(account.credits());
    }
    if (debits.compareTo(credits) != 0) {
      found.add(new ReconciliationMismatch(Kind.LEDGER_TOTAL_UNBALANCED, Map.of()));
    }
  }

  // ---------------------------------------------------------------------------------------------

  private static ReconciliationMismatch mismatch(Kind kind, Object... namesAndIds) {
    Map<String, Object> ids = new LinkedHashMap<>();
    for (int i = 0; i < namesAndIds.length; i += 2) {
      ids.put((String) namesAndIds[i], namesAndIds[i + 1]);
    }
    return new ReconciliationMismatch(kind, ids);
  }
}
