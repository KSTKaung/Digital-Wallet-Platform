package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.common.database.TransactionTimeouts;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.common.time.BusinessTimeProperties;
import com.portfolio.wallet.ledger.application.LedgerPostingService;
import com.portfolio.wallet.wallet.domain.BusinessDay;
import com.portfolio.wallet.wallet.domain.InsufficientReservedBalanceException;
import com.portfolio.wallet.wallet.domain.LimitPolicy;
import com.portfolio.wallet.wallet.domain.Operation;
import com.portfolio.wallet.wallet.domain.TransferCheckResult;
import com.portfolio.wallet.wallet.domain.TransferRules;
import com.portfolio.wallet.wallet.domain.Wallet;
import com.portfolio.wallet.wallet.domain.WalletStatus;
import com.portfolio.wallet.wallet.persistence.DailyLimitUsageRepository;
import com.portfolio.wallet.wallet.persistence.DailyLimitUsageRow;
import com.portfolio.wallet.wallet.persistence.WalletRepository;
import com.portfolio.wallet.wallet.persistence.WalletRow;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The wallet side of transfers: the API the {@code transfer} module calls (documentations/06
 * MVP-ADR-03, -08, -18, -20, -22, -24; doc 07 §8; doc 08 §4C.2 Q3, Q4, §4C.3, §4C.4 criteria 2, 5,
 * 6, 10, 11).
 *
 * <h2>Pre-check (Q3 step 5)</h2>
 *
 * {@link #precheck} runs outside any transaction and takes no lock. It reads the sender wallet and
 * the day's transfer usage as committed now and applies {@link TransferRules}, so an obvious
 * refusal ends the request before a risk assessment is created. It is only a shortcut: the money
 * transaction repeats every check under its locks.
 *
 * <h2>The money transaction</h2>
 *
 * Every other method joins the caller's transaction ({@link Propagation#MANDATORY}): the caller
 * (the transfer service, or 5b's review) opened it through its idempotency engine or with its own
 * transfer-row lock, and applied the D14 timeouts first ({@link #lock} refuses otherwise). In the
 * lock order of every money flow (doc 07 §8):
 *
 * <ol>
 *   <li>(the caller's idempotency row or transfer row);
 *   <li>{@link #lock}: both wallets {@code FOR UPDATE} in ascending id order, in one statement;
 *   <li>the sender's {@code TRANSFER} {@code daily_limit_usage} row {@code FOR UPDATE}, inside
 *       {@link #complete}, {@link #hold}, {@link #release} or {@link #approve};
 *   <li>the customer ledger accounts {@code FOR SHARE}, taken by the ledger entry trigger when
 *       {@link #complete} or {@link #approve} posts.
 * </ol>
 *
 * Under the locks {@link #complete} and {@link #hold} re-check, in the order of Q3: the recipient
 * (no longer {@code ACTIVE}: the identical {@code 404} of {@link ApiException#notFound()}, because
 * a transfer to a frozen or closed wallet never returns {@code WALLET_FROZEN}, MVP-ADR-20), then
 * the sender ({@link TransferRules}). Snapshots and usage are written with their optimistic {@code
 * version} check (MVP-ADR-08); under the row locks a mismatch means a code path skipped the lock,
 * so it is an {@link IllegalStateException} ({@code 500}), never retried (doc 07 §8).
 *
 * <h2>The four operations</h2>
 *
 * <ul>
 *   <li>{@link #complete} ({@code ALLOW}, {@code MONITOR}): sender available − amount, recipient
 *       available + amount, the day's {@code used} + amount, and the balanced {@code TRANSFER}
 *       ledger transaction;
 *   <li>{@link #hold} ({@code MANUAL_REVIEW}): sender available → reserved, the day's {@code
 *       reserved} + amount; no ledger entry, the recipient untouched. It returns the day, which the
 *       transfer stores as {@code limit_date};
 *   <li>{@link #release} (cancel, reject, expiry, an approval that finds a wallet unavailable):
 *       reserved → available, and {@code reserved} − amount on the <b>stored</b> day, in any wallet
 *       status (MVP-ADR-24);
 *   <li>{@link #approve} (5b): if both wallets are {@code ACTIVE}, sender reserved − amount,
 *       recipient available + amount, {@code reserved} → {@code used} on the stored day, and the
 *       ledger transaction; otherwise the hold is released as by {@link #release}, and the outcome
 *       names the unavailable wallet.
 * </ul>
 *
 * Each keeps the reconciliation rule available + reserved = ledger balance for both wallets
 * (MVP-ADR-03 check 1). A release or capture that would take more than is reserved, on the wallet
 * or on the day's usage, is an {@link IllegalStateException}: a hold always reserved exactly that
 * amount on that day, so it is a programming error or inconsistent data, never a business refusal.
 *
 * <h2>Refusals</h2>
 *
 * {@code 409 WALLET_FROZEN} (sender), {@code 400 INVALID_AMOUNT} below the minimum, {@code 409
 * SINGLE_LIMIT_EXCEEDED}, {@code 409 DAILY_LIMIT_EXCEEDED} (used + reserved, per tier, on the
 * Asia/Bangkok day of the injected {@link Clock}), {@code 409 INSUFFICIENT_BALANCE} (available
 * only), with the messages of deposits and withdrawals. A {@code CLOSED} sender has no documented
 * business code and is a {@code 500}, as on the money endpoints today (doc 07 D21, Z8).
 *
 * <p>Nothing here is logged: no id, amount or balance.
 */
@Service
public class WalletTransferService {

  static final String CLOSED = "wallet is closed";

  private static final String TRANSFER = Operation.TRANSFER.name();

  private final WalletRepository wallets;
  private final DailyLimitUsageRepository usages;
  private final LedgerPostingService ledger;
  private final TransferRules rules;
  private final BusinessDay businessDay;
  private final Clock clock;
  private final TransactionTimeouts timeouts;

  public WalletTransferService(
      WalletRepository wallets,
      DailyLimitUsageRepository usages,
      LedgerPostingService ledger,
      LimitPolicy limits,
      BusinessTimeProperties businessTime,
      Clock clock,
      TransactionTimeouts timeouts) {
    this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
    this.wallets = Objects.requireNonNull(wallets, "wallets");
    this.usages = Objects.requireNonNull(usages, "usages");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.rules = new TransferRules(limits);
    this.businessDay = new BusinessDay(businessTime.businessZone());
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  // Pre-check ------------------------------------------------------------------------------------

  /**
   * The read-only pre-check of Q3 step 5, outside any transaction and without locks: the sender's
   * status, minimum, per-transaction maximum, daily limit (used + reserved today) and available
   * balance, as committed now.
   *
   * @param amount a positive THB amount, already validated by the caller (Q3 step 1)
   *     <p>A refusal here is stored for the request's {@code Idempotency-Key} like one found under
   *     the locks: the caller passes it to {@code TransferIdempotency#refuse} (doc 08 §5, step 2
   *     notes), never around the engine.
   * @return the sender wallet as read (its {@code createdAt} is the wallet age for the risk
   *     request) and the Bangkok business day of the pre-check, for a {@code BLOCKED} row
   * @throws ApiException {@code KYC_REQUIRED} if the customer has no wallet, or the refusal of the
   *     first rule that fails
   * @throws org.springframework.transaction.IllegalTransactionStateException inside a transaction
   */
  @Transactional(propagation = Propagation.NEVER)
  public TransferPrecheck precheck(UUID senderCustomerId, Money amount) {
    Objects.requireNonNull(senderCustomerId, "senderCustomerId");
    requireTransferAmount(amount);
    WalletRow row =
        wallets
            .findByCustomer(senderCustomerId)
            .orElseThrow(
                () -> new ApiException(ErrorCode.KYC_REQUIRED, MoneyMovementService.KYC_REQUIRED));
    Wallet sender = WalletRows.toDomain(row);
    LocalDate today = businessDay.today(clock);
    DailyLimitUsageRow usage =
        usages
            .find(sender.id(), TRANSFER, today)
            .orElse(new DailyLimitUsageRow(BigDecimal.ZERO, BigDecimal.ZERO, 0));
    refuseUnlessAllowed(sender, amount, usage);
    return new TransferPrecheck(LockedTransferWallets.view(row), today);
  }

  // The money transaction ------------------------------------------------------------------------

  /**
   * Locks both wallets {@code FOR UPDATE} in ascending id order in one statement ({@link
   * WalletRepository#lockBothForUpdate}).
   *
   * @throws IllegalArgumentException if the ids are equal (the caller refuses {@code SELF_TRANSFER}
   *     before the key is used)
   * @throws IllegalStateException if the D14 timeouts were not applied in this transaction ({@link
   *     TransactionTimeouts#apply()}, enforced rather than trusted), or a wallet does not exist
   *     (wallets are never deleted, and the caller resolved both before)
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public LockedTransferWallets lock(UUID senderWalletId, UUID recipientWalletId) {
    if (!timeouts.applied()) {
      throw new IllegalStateException(
          "money locks need the D14 timeouts: apply them first in this transaction");
    }
    Objects.requireNonNull(senderWalletId, "senderWalletId");
    Objects.requireNonNull(recipientWalletId, "recipientWalletId");
    if (senderWalletId.equals(recipientWalletId)) {
      throw new IllegalArgumentException("a transfer needs two different wallets");
    }
    List<WalletRow> rows = wallets.lockBothForUpdate(senderWalletId, recipientWalletId);
    WalletRow sender = find(rows, senderWalletId);
    WalletRow recipient = find(rows, recipientWalletId);
    return new LockedTransferWallets(sender, recipient);
  }

  /**
   * Completes a transfer ({@code ALLOW}, {@code MONITOR}): re-checks under the locks, moves the
   * money and posts the ledger, as described on the class.
   *
   * @param reference the transfer's unique reference, also the ledger {@code transaction_reference}
   * @throws ApiException the identical {@code NOT_FOUND} for a recipient that is no longer {@code
   *     ACTIVE}, or a sender refusal
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public TransferSettlement complete(LockedTransferWallets locked, Money amount, String reference) {
    Objects.requireNonNull(locked, "locked");
    requireTransferAmount(amount);
    requireReference(reference);
    locked.claim();
    Wallet sender = WalletRows.toDomain(locked.senderRow());
    Wallet recipient = WalletRows.toDomain(locked.recipientRow());
    requireEligibleRecipient(recipient);
    LocalDate limitDate = businessDay.today(clock);
    DailyLimitUsageRow usage = usages.lockOrCreate(sender.id(), TRANSFER, limitDate);
    refuseUnlessAllowed(sender, amount, usage);

    sender.debit(amount);
    recipient.credit(amount);
    writeBalances(sender);
    writeBalances(recipient);
    writeUsage(sender.id(), limitDate, usage, usage.used().add(amount.amount()), usage.reserved());
    UUID ledgerTransactionId = ledger.postTransfer(sender.id(), recipient.id(), reference, amount);
    return new TransferSettlement(limitDate, ledgerTransactionId);
  }

  /**
   * Holds a transfer for review ({@code MANUAL_REVIEW}): re-checks under the locks, then moves the
   * amount from the sender's available to its reserved balance and reserves it on today's usage. No
   * ledger entry; the recipient is not changed.
   *
   * @return the Asia/Bangkok day the amount was reserved on; store it as the transfer's {@code
   *     limit_date} and pass it to {@link #release} or {@link #approve} (MVP-ADR-18, -22)
   * @throws ApiException the identical {@code NOT_FOUND} for a recipient that is no longer {@code
   *     ACTIVE}, or a sender refusal
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public LocalDate hold(LockedTransferWallets locked, Money amount) {
    Objects.requireNonNull(locked, "locked");
    requireTransferAmount(amount);
    locked.claim();
    Wallet sender = WalletRows.toDomain(locked.senderRow());
    requireEligibleRecipient(WalletRows.toDomain(locked.recipientRow()));
    LocalDate limitDate = businessDay.today(clock);
    DailyLimitUsageRow usage = usages.lockOrCreate(sender.id(), TRANSFER, limitDate);
    refuseUnlessAllowed(sender, amount, usage);

    sender.hold(amount);
    writeBalances(sender);
    writeUsage(sender.id(), limitDate, usage, usage.used(), usage.reserved().add(amount.amount()));
    return limitDate;
  }

  /**
   * Releases a held transfer (cancel, reject, expiry): the sender's reserved balance back to
   * available, and the reservation off the stored day's usage. Allowed in every wallet status
   * (MVP-ADR-24). The recipient is not changed. An approval uses {@link #approve}, which releases
   * by itself when a wallet is unavailable.
   *
   * @param limitDate the transfer's stored {@code limit_date}, never today (MVP-ADR-22)
   * @throws IllegalStateException if the wallet or that day's usage reserves less than the amount
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void release(LockedTransferWallets locked, Money amount, LocalDate limitDate) {
    Objects.requireNonNull(locked, "locked");
    requireTransferAmount(amount);
    Objects.requireNonNull(limitDate, "limitDate");
    locked.claim();
    releaseClaimed(locked, amount, limitDate);
  }

  /**
   * Approves a held transfer (5b; MVP-ADR-24 "Approve", doc 08 §4C.4 criterion 15). Both wallets
   * are checked under the locks, the sender first (MVP-ADR-24 review case 3):
   *
   * <ul>
   *   <li>both {@code ACTIVE}: the hold is captured, the sender's reserved amount leaves the
   *       wallet, the recipient is credited, the stored day's reservation becomes used, and the
   *       ledger is posted: {@link ApprovalOutcome.Captured};
   *   <li>otherwise the hold is released exactly as by {@link #release} and nothing is posted:
   *       {@link ApprovalOutcome.ReleasedWalletUnavailable} names the wallet and its status, so the
   *       caller records the system reason and rejects the transfer with {@code
   *       WALLET_UNAVAILABLE}.
   * </ul>
   *
   * There is no public capture: an approval cannot skip the wallet check.
   *
   * @param limitDate the transfer's stored {@code limit_date}, never today (MVP-ADR-22)
   * @param reference the transfer's unique reference, also the ledger {@code transaction_reference}
   * @throws IllegalStateException if the wallet or that day's usage reserves less than the amount
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public ApprovalOutcome approve(
      LockedTransferWallets locked, Money amount, LocalDate limitDate, String reference) {
    Objects.requireNonNull(locked, "locked");
    requireTransferAmount(amount);
    Objects.requireNonNull(limitDate, "limitDate");
    requireReference(reference);
    locked.claim();
    UnavailableWallet unavailable =
        UnavailableWallet.of(locked.senderRow().status(), locked.recipientRow().status());
    if (unavailable != null) {
      releaseClaimed(locked, amount, limitDate);
      return new ApprovalOutcome.ReleasedWalletUnavailable(unavailable);
    }
    return new ApprovalOutcome.Captured(captureClaimed(locked, amount, limitDate, reference));
  }

  /**
   * The capture step of {@link #approve}, without its wallet check; package-private so no caller
   * outside the wallet module can capture a hold while a wallet is unavailable.
   *
   * @throws IllegalStateException if a wallet is not {@code ACTIVE}, or the wallet or that day's
   *     usage reserves less than the amount
   */
  @Transactional(propagation = Propagation.MANDATORY)
  TransferSettlement capture(
      LockedTransferWallets locked, Money amount, LocalDate limitDate, String reference) {
    Objects.requireNonNull(locked, "locked");
    requireTransferAmount(amount);
    Objects.requireNonNull(limitDate, "limitDate");
    requireReference(reference);
    locked.claim();
    return captureClaimed(locked, amount, limitDate, reference);
  }

  private void releaseClaimed(LockedTransferWallets locked, Money amount, LocalDate limitDate) {
    Wallet sender = WalletRows.toDomain(locked.senderRow());
    try {
      sender.releaseHold(amount);
    } catch (InsufficientReservedBalanceException e) {
      throw new IllegalStateException("release exceeds the wallet's reserved balance", e);
    }
    DailyLimitUsageRow usage = lockReservedUsage(sender.id(), limitDate, amount);
    writeBalances(sender);
    writeUsage(
        sender.id(), limitDate, usage, usage.used(), usage.reserved().subtract(amount.amount()));
  }

  private TransferSettlement captureClaimed(
      LockedTransferWallets locked, Money amount, LocalDate limitDate, String reference) {
    Wallet sender = WalletRows.toDomain(locked.senderRow());
    Wallet recipient = WalletRows.toDomain(locked.recipientRow());
    if (sender.status() != WalletStatus.ACTIVE || recipient.status() != WalletStatus.ACTIVE) {
      throw new IllegalStateException("a capture needs two active wallets; release the hold");
    }
    try {
      sender.captureHold(amount);
    } catch (InsufficientReservedBalanceException e) {
      throw new IllegalStateException("capture exceeds the wallet's reserved balance", e);
    }
    recipient.credit(amount);
    DailyLimitUsageRow usage = lockReservedUsage(sender.id(), limitDate, amount);
    writeBalances(sender);
    writeBalances(recipient);
    writeUsage(
        sender.id(),
        limitDate,
        usage,
        usage.used().add(amount.amount()),
        usage.reserved().subtract(amount.amount()));
    UUID ledgerTransactionId = ledger.postTransfer(sender.id(), recipient.id(), reference, amount);
    return new TransferSettlement(limitDate, ledgerTransactionId);
  }

  // Helpers --------------------------------------------------------------------------------------

  private static WalletRow find(List<WalletRow> rows, UUID id) {
    return rows.stream()
        .filter(row -> row.id().equals(id))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("transfer wallet not found under its lock"));
  }

  /**
   * MVP-ADR-20: a missing, frozen or closed recipient is the identical 404, never WALLET_FROZEN.
   */
  private static void requireEligibleRecipient(Wallet recipient) {
    if (recipient.status() != WalletStatus.ACTIVE) {
      throw ApiException.notFound();
    }
  }

  private void refuseUnlessAllowed(Wallet sender, Money amount, DailyLimitUsageRow usage) {
    Money used = Money.of(usage.used(), sender.currency());
    Money reserved = Money.of(usage.reserved(), sender.currency());
    TransferCheckResult result = rules.check(sender, amount, used, reserved);
    switch (result) {
      case ALLOWED -> {}
      case SENDER_FROZEN ->
          throw new ApiException(ErrorCode.WALLET_FROZEN, MoneyMovementService.FROZEN);
      // No business code for a closed wallet (doc 07 D21, Z8): a server error, never stored.
      case SENDER_CLOSED -> throw new IllegalStateException(CLOSED);
      case BELOW_MINIMUM ->
          throw new ApiException(ErrorCode.INVALID_AMOUNT, MoneyMovementService.BELOW_MINIMUM);
      case SINGLE_LIMIT_EXCEEDED ->
          throw new ApiException(
              ErrorCode.SINGLE_LIMIT_EXCEEDED, MoneyMovementService.SINGLE_LIMIT);
      case DAILY_LIMIT_EXCEEDED ->
          throw new ApiException(ErrorCode.DAILY_LIMIT_EXCEEDED, MoneyMovementService.DAILY_LIMIT);
      case INSUFFICIENT_BALANCE ->
          throw new ApiException(
              ErrorCode.INSUFFICIENT_BALANCE, MoneyMovementService.INSUFFICIENT_BALANCE);
    }
  }

  /** The stored day's usage row, locked; it must reserve at least the amount. */
  private DailyLimitUsageRow lockReservedUsage(UUID walletId, LocalDate limitDate, Money amount) {
    DailyLimitUsageRow usage =
        usages
            .lockExisting(walletId, TRANSFER, limitDate)
            .orElseThrow(() -> new IllegalStateException("no usage row for the held transfer"));
    if (usage.reserved().compareTo(amount.amount()) < 0) {
      throw new IllegalStateException("the day's reserved usage is lower than the held amount");
    }
    return usage;
  }

  private void writeBalances(Wallet wallet) {
    if (wallets.updateBalances(
            wallet.id(),
            wallet.availableBalance().amount(),
            wallet.reservedBalance().amount(),
            wallet.version())
        != 1) {
      throw new IllegalStateException("wallet version changed under its row lock");
    }
  }

  private void writeUsage(
      UUID walletId,
      LocalDate limitDate,
      DailyLimitUsageRow usage,
      BigDecimal used,
      BigDecimal reserved) {
    if (used.signum() < 0 || reserved.signum() < 0) {
      throw new IllegalStateException("daily usage must not become negative");
    }
    if (usages.updateUsedAndReserved(walletId, TRANSFER, limitDate, used, reserved, usage.version())
        != 1) {
      throw new IllegalStateException("daily usage version changed under its row lock");
    }
  }

  /** Validated by the caller (Q3 step 1); anything else here is a programming error. */
  private static void requireTransferAmount(Money amount) {
    Objects.requireNonNull(amount, "amount");
    if (!Money.THB.equals(amount.currency())) {
      throw new IllegalArgumentException("transfers are in THB only");
    }
    if (!amount.isPositive()) {
      throw new IllegalArgumentException("a transfer amount must be positive");
    }
  }

  private static void requireReference(String reference) {
    Objects.requireNonNull(reference, "reference");
    if (reference.isBlank()) {
      throw new IllegalArgumentException("reference must not be blank");
    }
  }
}
