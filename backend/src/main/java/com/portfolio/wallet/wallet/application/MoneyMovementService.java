package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.audit.application.ActorType;
import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.common.correlation.CorrelationId;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.money.AmountTooLargeException;
import com.portfolio.wallet.common.money.InvalidAmountException;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.common.time.BusinessTimeProperties;
import com.portfolio.wallet.ledger.application.LedgerPostingService;
import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.MoneyEmailValues;
import com.portfolio.wallet.notification.application.NotificationOutbox;
import com.portfolio.wallet.wallet.domain.BusinessDay;
import com.portfolio.wallet.wallet.domain.InsufficientBalanceException;
import com.portfolio.wallet.wallet.domain.LimitPolicy;
import com.portfolio.wallet.wallet.domain.MovementIdentity;
import com.portfolio.wallet.wallet.domain.Operation;
import com.portfolio.wallet.wallet.domain.RequestFingerprint;
import com.portfolio.wallet.wallet.domain.Wallet;
import com.portfolio.wallet.wallet.domain.WalletStatus;
import com.portfolio.wallet.wallet.persistence.DailyLimitUsageRepository;
import com.portfolio.wallet.wallet.persistence.DailyLimitUsageRow;
import com.portfolio.wallet.wallet.persistence.MoneyMovementRepository;
import com.portfolio.wallet.wallet.persistence.MoneyMovementRepository.Table;
import com.portfolio.wallet.wallet.persistence.MoneyMovementRow;
import com.portfolio.wallet.wallet.persistence.WalletRepository;
import com.portfolio.wallet.wallet.persistence.WalletRow;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * Simulated deposits and withdrawals (documentations/02 §2.3; doc 05 §4; doc 06 MVP-ADR-03, -06,
 * -08, -13, -18, -21, -22; doc 07 §8, D10; doc 08 §4B.2 Q14 and owner requirements 1–4, 6).
 *
 * <h2>Before the money transaction</h2>
 *
 * Request checks that do not consume the {@code Idempotency-Key} (doc 02 §1.4): the amount has at
 * most 2 decimals and is positive ({@code 400 INVALID_AMOUNT}, never rounded), the currency is
 * {@code THB} ({@code 400 CURRENCY_MISMATCH}), and the customer has a wallet, which exists only
 * after KYC approval ({@code 403 KYC_REQUIRED}, MVP-ADR-10). The KYC check is a precondition, so a
 * customer who is approved later can retry with the same key.
 *
 * <h2>The money transaction</h2>
 *
 * One database transaction, opened by {@link IdempotencyService#execute}, in the lock order of
 * every money flow (doc 07 §8):
 *
 * <ol>
 *   <li>the idempotency row (a replay or {@code 409} ends here);
 *   <li>if the key's record was purged but a deposit or withdrawal with this key exists, it is
 *       returned when customer, operation, wallet, amount and currency match, otherwise {@code 409
 *       IDEMPOTENCY_KEY_REUSED} (Q14);
 *   <li>the wallet {@code FOR UPDATE}; a {@code FROZEN} wallet is {@code 409 WALLET_FROZEN};
 *   <li>the day's {@code daily_limit_usage} row {@code FOR UPDATE}, on the Asia/Bangkok business
 *       day of the injected {@link Clock}; then minimum ({@code 400 INVALID_AMOUNT}),
 *       per-transaction maximum ({@code 409 SINGLE_LIMIT_EXCEEDED}) and daily limit ({@code 409
 *       DAILY_LIMIT_EXCEEDED}), all from configuration (MVP-ADR-18);
 *   <li>a withdrawal needs the available balance ({@code 409 INSUFFICIENT_BALANCE}; no overdraft);
 *   <li>the snapshot and the day's usage are updated with their {@code version} checks;
 *   <li>the balanced ledger transaction (deposit: DR {@code SYS-CASH-CLEARING} / CR customer;
 *       withdrawal the reverse); the entry trigger locks the customer ledger account {@code FOR
 *       SHARE} and refuses it unless it is {@code ACTIVE};
 *   <li>the {@code COMPLETED} business row with {@code ledger_status = POSTED}, the audit row, and
 *       the response stored on the idempotency record.
 * </ol>
 *
 * Any failure rolls all of it back. {@code WALLET_FROZEN}, {@code SINGLE_LIMIT_EXCEEDED}, {@code
 * DAILY_LIMIT_EXCEEDED} and {@code INSUFFICIENT_BALANCE} are then stored for the key in their own
 * transaction and replayed (D05); everything else leaves no record. The response, with {@code
 * ledgerStatus = POSTED}, reaches the caller only after the commit.
 *
 * <p>Under the row locks the {@code version} cannot have moved; a mismatch means a code path
 * skipped the lock, so the movement fails with a server error and is not retried (doc 07 §8).
 *
 * <p>A completed movement writes a {@code DEPOSIT_COMPLETED} or {@code WITHDRAWAL_COMPLETED} outbox
 * row for the customer in the same transaction (doc 08 §4C.2 Q9; MVP-ADR-25): amount, currency and
 * server reference only, sent after the commit. A refusal, a replay (in the key's window or after
 * it, Q14), a {@code 409} and a rollback write none. Nothing here is logged.
 */
@Service
public class MoneyMovementService {

  static final String KYC_REQUIRED = "KYC approval is required before moving money";
  static final String INSUFFICIENT_BALANCE = "Available balance is lower than the requested amount";
  static final String SINGLE_LIMIT = "Amount exceeds the per-transaction limit";
  static final String DAILY_LIMIT = "Amount exceeds the daily limit";
  static final String BELOW_MINIMUM = "amount: is below the minimum";
  static final String FROZEN = "Wallet is frozen";
  static final String TOO_MANY_DECIMALS = "amount: must have at most 2 decimal places";
  static final String NOT_POSITIVE = "amount: must be greater than zero";
  static final String TOO_LARGE = "amount: is too large";
  static final String CURRENCY = "currency: only THB is supported";

  static final String COMPLETED = "COMPLETED";
  static final String POSTED = "POSTED";
  static final int CREATED = 201;

  /** The deposit's free-text note in the request hash (doc 07 D10). */
  static final String REFERENCE_FIELD = "reference";

  private final WalletRepository wallets;
  private final DailyLimitUsageRepository usages;
  private final MoneyMovementRepository movements;
  private final LedgerPostingService ledger;
  private final AuditService audit;
  private final IdempotencyService idempotency;
  private final LimitPolicy limits;
  private final BusinessDay businessDay;
  private final Clock clock;
  private final JsonMapper json;
  private final NotificationOutbox outbox;

  public MoneyMovementService(
      WalletRepository wallets,
      DailyLimitUsageRepository usages,
      MoneyMovementRepository movements,
      LedgerPostingService ledger,
      AuditService audit,
      IdempotencyService idempotency,
      LimitPolicy limits,
      BusinessTimeProperties businessTime,
      Clock clock,
      JsonMapper json,
      NotificationOutbox outbox) {
    this.wallets = wallets;
    this.usages = usages;
    this.movements = movements;
    this.ledger = ledger;
    this.audit = audit;
    this.idempotency = idempotency;
    this.limits = limits;
    this.businessDay = new BusinessDay(businessTime.businessZone());
    this.clock = clock;
    this.json = json;
    this.outbox = Objects.requireNonNull(outbox, "outbox");
  }

  /**
   * {@code POST /api/v1/wallet/deposits}.
   *
   * @param note the client's {@code reference}, a free-text note (doc 07 D10), already validated by
   *     the web layer; may be null
   */
  public MoneyMovementView deposit(
      UUID customerId,
      UUID idempotencyKey,
      BigDecimal amount,
      String currency,
      String note,
      InetAddress clientIp) {
    return move(Kind.DEPOSIT, customerId, idempotencyKey, amount, currency, note, clientIp);
  }

  /** {@code POST /api/v1/wallet/withdrawals}. */
  public MoneyMovementView withdraw(
      UUID customerId,
      UUID idempotencyKey,
      BigDecimal amount,
      String currency,
      InetAddress clientIp) {
    return move(Kind.WITHDRAWAL, customerId, idempotencyKey, amount, currency, null, clientIp);
  }

  private MoneyMovementView move(
      Kind kind,
      UUID customerId,
      UUID idempotencyKey,
      BigDecimal amount,
      String currency,
      String note,
      InetAddress clientIp) {
    Objects.requireNonNull(customerId, "customerId");
    Objects.requireNonNull(idempotencyKey, "idempotencyKey");
    Money money = requestedAmount(amount, currency);
    // Precondition, before the key is used: no wallet means no KYC approval (MVP-ADR-10).
    WalletRow current =
        wallets
            .findByCustomer(customerId)
            .orElseThrow(() -> new ApiException(ErrorCode.KYC_REQUIRED, KYC_REQUIRED));
    RequestFingerprint.Builder fingerprint =
        RequestFingerprint.builder(kind.operation, customerId, money);
    if (kind == Kind.DEPOSIT) {
      fingerprint.field(REFERENCE_FIELD, note);
    }
    IdempotentRequest request = new IdempotentRequest(idempotencyKey, fingerprint.build());
    IdempotentResult result =
        idempotency.execute(
            request,
            () -> stored(kind, customerId, idempotencyKey, current.id(), money, note, clientIp),
            refusal ->
                audit.record(
                    refusalEntry(kind, customerId, current.id(), money, refusal, clientIp)));
    return json.readValue(result.response().jsonBody(), MoneyMovementView.class);
  }

  /**
   * The audit row of a stored refusal (owner, 2026-11-04, W5): written in the transaction that
   * stores the refusal, so it exists exactly when the refusal is stored, once per key. The same
   * non-sensitive fields as a success where they exist, and the error code; never the note.
   */
  private static AuditEntry refusalEntry(
      Kind kind,
      UUID customerId,
      UUID walletId,
      Money amount,
      ApiException refusal,
      InetAddress clientIp) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("walletId", walletId.toString());
    value.put("amount", amount.amount().toPlainString());
    value.put("currency", amount.currency().getCurrencyCode());
    value.put("errorCode", refusal.errorCode().name());
    return AuditEntry.of(ActorType.CUSTOMER, kind.name(), kind.name(), AuditResult.FAILURE)
        .withActor(customerId)
        .withClientIp(clientIp)
        .withNewValue(value);
  }

  /** Inside the money transaction, after the idempotency row. */
  private StoredResponse stored(
      Kind kind,
      UUID customerId,
      UUID idempotencyKey,
      UUID walletId,
      Money amount,
      String note,
      InetAddress clientIp) {
    // Q14: the key's record is gone, but its movement is not.
    var earlier = movements.findByCustomerAndKey(kind.table, customerId, idempotencyKey);
    if (earlier.isPresent()) {
      MoneyMovementRow row = earlier.get();
      IdempotencyService.requireSameMovement(
          new MovementIdentity(customerId, kind.operation, walletId, amount),
          new MovementIdentity(
              row.customerId(),
              kind.operation,
              row.walletId(),
              Money.of(row.amount(), Currency.getInstance(row.currency()))));
      return response(view(row, walletNumberOf(customerId)));
    }

    WalletRow locked =
        wallets
            .findByCustomerForUpdate(customerId)
            .orElseThrow(() -> new IllegalStateException("wallet disappeared under its lock"));
    Wallet wallet = WalletRows.toDomain(locked);
    if (wallet.status() == WalletStatus.FROZEN) {
      throw new ApiException(ErrorCode.WALLET_FROZEN, FROZEN);
    }

    LocalDate limitDate = businessDay.today(clock);
    DailyLimitUsageRow usage = usages.lockOrCreate(wallet.id(), kind.operation.name(), limitDate);
    Money used = Money.of(usage.used(), wallet.currency());
    checkLimits(kind, wallet, amount, used, Money.of(usage.reserved(), wallet.currency()));

    Money before = wallet.availableBalance();
    if (kind == Kind.DEPOSIT) {
      wallet.credit(amount);
    } else {
      try {
        wallet.debit(amount);
      } catch (InsufficientBalanceException e) {
        throw new ApiException(ErrorCode.INSUFFICIENT_BALANCE, INSUFFICIENT_BALANCE);
      }
    }
    if (wallets.updateAvailableBalance(
            wallet.id(), wallet.availableBalance().amount(), wallet.version())
        != 1) {
      throw new IllegalStateException("wallet version changed under its row lock");
    }
    if (usages.updateUsed(
            wallet.id(),
            kind.operation.name(),
            limitDate,
            used.plus(amount).amount(),
            usage.version())
        != 1) {
      throw new IllegalStateException("daily usage version changed under its row lock");
    }

    String reference = kind.newReference();
    UUID ledgerTransactionId =
        kind == Kind.DEPOSIT
            ? ledger.postDeposit(wallet.id(), reference, amount)
            : ledger.postWithdrawal(wallet.id(), reference, amount);

    MoneyMovementRow row =
        new MoneyMovementRow(
            UUID.randomUUID(),
            wallet.id(),
            customerId,
            amount.amount(),
            amount.currency().getCurrencyCode(),
            COMPLETED,
            idempotencyKey,
            reference,
            limitDate,
            POSTED,
            CorrelationId.current().orElse(null));
    movements.insert(kind.table, row, kind == Kind.DEPOSIT ? note : null);
    audit.record(auditEntry(kind, row, before, wallet, ledgerTransactionId, clientIp));
    // Q9: written in this transaction, sent after the commit (MVP-ADR-25); the customer id is the
    // auth user id (usr.customers.user_id), and no address is looked up under the wallet lock.
    outbox.enqueue(
        customerId, kind.email, MoneyEmailValues.of(row.amount(), row.currency(), row.reference()));
    return response(view(row, wallet.walletNumber().value()));
  }

  private void checkLimits(Kind kind, Wallet wallet, Money amount, Money used, Money reserved) {
    switch (limits.check(kind.operation, wallet.tier(), amount, used, reserved)) {
      case WITHIN_LIMITS -> {}
      case BELOW_MINIMUM -> throw new ApiException(ErrorCode.INVALID_AMOUNT, BELOW_MINIMUM);
      case SINGLE_LIMIT_EXCEEDED ->
          throw new ApiException(ErrorCode.SINGLE_LIMIT_EXCEEDED, SINGLE_LIMIT);
      case DAILY_LIMIT_EXCEEDED ->
          throw new ApiException(ErrorCode.DAILY_LIMIT_EXCEEDED, DAILY_LIMIT);
    }
  }

  /**
   * The amount first, then the currency (doc 02 §2.3 step order; owner, 2026-11-04, Y2): within
   * {@code NUMERIC(19,2)} ("too large", Y3), at most 2 decimals and never rounded (MVP-ADR-21), and
   * positive, all {@code INVALID_AMOUNT}; then THB only ({@code CURRENCY_MISMATCH}). Messages name
   * the field, never the value.
   */
  private static Money requestedAmount(BigDecimal amount, String currency) {
    Objects.requireNonNull(amount, "amount");
    Money money;
    try {
      money = Money.of(amount, Money.THB);
    } catch (AmountTooLargeException e) {
      throw new ApiException(ErrorCode.INVALID_AMOUNT, TOO_LARGE);
    } catch (InvalidAmountException e) {
      throw new ApiException(ErrorCode.INVALID_AMOUNT, TOO_MANY_DECIMALS);
    }
    if (!money.isPositive()) {
      throw new ApiException(ErrorCode.INVALID_AMOUNT, NOT_POSITIVE);
    }
    if (!Money.THB.getCurrencyCode().equals(currency)) {
      throw new ApiException(ErrorCode.CURRENCY_MISMATCH, CURRENCY);
    }
    return money;
  }

  private String walletNumberOf(UUID customerId) {
    return wallets
        .findByCustomer(customerId)
        .orElseThrow(() -> new IllegalStateException("wallet disappeared"))
        .walletNumber();
  }

  private static MoneyMovementView view(MoneyMovementRow row, String walletNumber) {
    return new MoneyMovementView(
        row.id(),
        walletNumber,
        row.amount().setScale(Money.SCALE),
        row.status(),
        row.reference(),
        row.ledgerStatus());
  }

  private StoredResponse response(MoneyMovementView view) {
    return new StoredResponse(CREATED, json.writeValueAsString(view));
  }

  private static AuditEntry auditEntry(
      Kind kind,
      MoneyMovementRow row,
      Money before,
      Wallet wallet,
      UUID ledgerTransactionId,
      InetAddress clientIp) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("walletId", row.walletId().toString());
    value.put("amount", row.amount().toPlainString());
    value.put("currency", row.currency());
    value.put("status", row.status());
    value.put("reference", row.reference());
    value.put("ledgerTransactionId", ledgerTransactionId.toString());
    value.put("availableBalance", wallet.availableBalance().amount().toPlainString());
    return AuditEntry.of(ActorType.CUSTOMER, kind.name(), kind.name(), AuditResult.SUCCESS)
        .withActor(row.customerId())
        .withResourceId(row.id().toString())
        .withClientIp(clientIp)
        .withOldValue(Map.of("availableBalance", before.amount().toPlainString()))
        .withNewValue(value);
  }

  private enum Kind {
    DEPOSIT(Operation.DEPOSIT, Table.DEPOSITS, "DEP-", EmailKind.DEPOSIT_COMPLETED),
    WITHDRAWAL(Operation.WITHDRAWAL, Table.WITHDRAWALS, "WDR-", EmailKind.WITHDRAWAL_COMPLETED);

    private final Operation operation;
    private final Table table;
    private final String prefix;
    private final EmailKind email;

    Kind(Operation operation, Table table, String prefix, EmailKind email) {
      this.operation = operation;
      this.table = table;
      this.prefix = prefix;
      this.email = email;
    }

    /** {@code DEP-} or {@code WDR-} and 32 hex digits: 36 characters, within V3's 40. */
    String newReference() {
      return prefix + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
    }
  }
}
