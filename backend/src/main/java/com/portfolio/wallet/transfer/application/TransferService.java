package com.portfolio.wallet.transfer.application;

import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.common.correlation.CorrelationId;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.idempotency.IdempotentResult;
import com.portfolio.wallet.common.idempotency.StoredResponse;
import com.portfolio.wallet.common.money.AmountTooLargeException;
import com.portfolio.wallet.common.money.InvalidAmountException;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.common.security.ClientAddresses;
import com.portfolio.wallet.risk.application.AssessmentDecision;
import com.portfolio.wallet.risk.application.RiskAssessmentRequest;
import com.portfolio.wallet.risk.application.RiskAssessmentResult;
import com.portfolio.wallet.risk.application.RiskAssessmentService;
import com.portfolio.wallet.transfer.domain.TransferDetails;
import com.portfolio.wallet.transfer.domain.TransferIdentity;
import com.portfolio.wallet.transfer.domain.TransferRequestFingerprint;
import com.portfolio.wallet.transfer.domain.TransferStatus;
import com.portfolio.wallet.transfer.persistence.TransferRepository;
import com.portfolio.wallet.transfer.persistence.TransferRepository.NewTransfer;
import com.portfolio.wallet.transfer.persistence.TransferRow;
import com.portfolio.wallet.wallet.application.LockedTransferWallets;
import com.portfolio.wallet.wallet.application.TransferPrecheck;
import com.portfolio.wallet.wallet.application.TransferRecipient;
import com.portfolio.wallet.wallet.application.TransferSettlement;
import com.portfolio.wallet.wallet.application.WalletLookupService;
import com.portfolio.wallet.wallet.application.WalletTransferService;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.time.LocalDate;
import java.util.Currency;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/v1/transfers} (documentations/02 §2.4; doc 06 MVP-ADR-03, -06, -08, -18, -20,
 * -24; doc 08 §4C.2 Q3–Q6, §4C.3, §4C.4 criteria 1–6, 9, 14, 20; §5 notes).
 *
 * <h2>The check order of Q3</h2>
 *
 * <ol>
 *   <li><b>Validation</b>: the body and the {@code Idempotency-Key} in the web layer ({@code 400
 *       VALIDATION_ERROR}, including a {@code receiverWalletNumber} that is not 11 ASCII digits);
 *       then here the amount ({@code 400 INVALID_AMOUNT}: size, scale, zero or negative) and the
 *       currency ({@code 400 CURRENCY_MISMATCH}).
 *   <li><b>KYC</b> and 3. <b>recipient</b>: {@link WalletLookupService#resolveTransferRecipient}:
 *       no sender wallet is {@code 403 KYC_REQUIRED} (not counted); then, counted in the lookup's
 *       per-user and per-IP windows and audited as a lookup (Q5), the identical {@code 404} for a
 *       missing, frozen or closed wallet or a wrong check digit, then {@code 409 SELF_TRANSFER};
 *       over a lookup limit {@code 429} with {@code Retry-After}. Steps 1–3 do not use the key.
 *   <li><b>Early replay</b>: {@link TransferIdempotency#replayIfCompleted}: a repeated request is
 *       answered from its stored outcome, or {@code 409 IDEMPOTENCY_KEY_REUSED}, before any
 *       pre-check or assessment. If the key's record has expired or been purged but this sender has
 *       a transfer with this key, that transfer answers instead (Q14 applied to transfers, owner
 *       2026-11-11, option a, as for deposits and withdrawals): the same sender wallet, recipient
 *       wallet, amount and currency get its creation response ({@code 201} with the body of {@link
 *       TransferViews#asCreated}, or {@code 403 RISK_BLOCKED} for a {@code BLOCKED} row), anything
 *       else {@code 409 IDEMPOTENCY_KEY_REUSED}; the description is not compared (Z3). Nothing is
 *       assessed, written or audited. The same check runs again first inside every transaction the
 *       engine opens, so a row that appears after this read is answered the same way and nothing of
 *       the new attempt is committed.
 *   <li><b>Pre-check</b>: {@link WalletTransferService#precheck} (frozen sender, minimum, single
 *       limit, daily limit used + reserved, balance), without locks. A storable refusal goes
 *       through {@link TransferIdempotency#refuse}, so it is stored for the key and replayed
 *       exactly like one found under the locks, with its {@code FAILURE} audit row in the storing
 *       transaction (doc 08 §5, step 2 notes); the minimum ({@code INVALID_AMOUNT}) is not stored.
 *   <li><b>Risk assessment</b>, in its own transaction ({@link RiskAssessmentService#assess}), for
 *       the id generated here: the JWT subject as the sender, the client IP, the sender wallet's
 *       database {@code created_at}, and whether a {@code COMPLETED} transfer from this sender to
 *       this recipient exists (this module's own table). Any failure ({@link Throwable}) is {@code
 *       500 SYSTEM_ERROR}: no transfer row, no hold, nothing stored for the key, and an audit row
 *       written independently (MVP-ADR-24; doc 08 §5, step 3 notes).
 *   <li><b>The money transaction</b>, through {@link TransferIdempotency}:
 *       <ul>
 *         <li>{@code ALLOW}, {@code MONITOR}: both wallets locked in one statement (the sender's
 *             wallet as the pre-check read it, and the resolved recipient), every check repeated
 *             under the locks ({@link WalletTransferService#complete}), the {@code COMPLETED} row
 *             with {@code ledger_status = POSTED}, its audit row, and the response, all in one
 *             commit; {@code 201};
 *         <li>{@code MANUAL_REVIEW}: the same locks and checks, then {@link
 *             WalletTransferService#hold}; the {@code PENDING_REVIEW} row stores the day the hold
 *             returned as {@code limit_date}, and its {@code review_expires_at} is {@code held_at}
 *             plus the configured review duration, both set by the database; {@code 201};
 *         <li>{@code BLOCK}: {@code 403 RISK_BLOCKED} is stored for the key, and the {@code
 *             BLOCKED} row (with the pre-check's {@code limit_date}) and its audit row are written
 *             in the transaction that stores it; no lock is taken and no balance changes.
 *       </ul>
 * </ol>
 *
 * A refusal found under the locks is stored by the engine with its audit row; a recipient that is
 * no longer {@code ACTIVE} under the lock is the identical {@code 404}, not stored and not counted
 * again (owner, 2026-11-09). A lock or statement timeout is {@code 500} and is never stored (Q6).
 * Two concurrent requests with the same key may both be assessed; only one transfer, hold or
 * movement results, and the other assessment stays as an orphan (Q4).
 *
 * <h2>Emails (Q9)</h2>
 *
 * Written by {@link TransferEmails} in the money transaction, so they commit or roll back with it
 * (MVP-ADR-25): {@code COMPLETED} emails the sender ({@code TRANSFER_SENT}) and the receiver
 * ({@code TRANSFER_RECEIVED}); {@code PENDING_REVIEW} emails the sender only ({@code
 * TRANSFER_HELD}). A {@code BLOCK}, a stored refusal, a replay (in the key's window or after it), a
 * {@code 409} and a rollback write none.
 *
 * <h2>Responses and logs</h2>
 *
 * The {@code 201} body is a {@link TransferView}; it is stored on the idempotency record and
 * reaches the client only after the commit, so {@code ledgerStatus = POSTED} is never returned for
 * an uncommitted posting. The description is stored and hashed but never logged, audited or
 * returned; the score, rule codes and weights never leave the risk module. Nothing here logs a
 * request value.
 */
@Service
public class TransferService {

  static final String RISK_BLOCKED = "The transfer was refused";
  static final String SYSTEM_ERROR = "An unexpected error occurred";
  static final String TOO_MANY_DECIMALS = "amount: must have at most 2 decimal places";
  static final String NOT_POSITIVE = "amount: must be greater than zero";
  static final String TOO_LARGE = "amount: is too large";
  static final String CURRENCY = "currency: only THB is supported";
  static final String POSTED = "POSTED";
  static final int CREATED = 201;

  private static final Logger log = LoggerFactory.getLogger(TransferService.class);

  private final WalletLookupService recipients;
  private final WalletTransferService wallets;
  private final TransferIdempotency idempotency;
  private final RiskAssessmentService risk;
  private final TransferRepository transfers;
  private final AuditService audit;
  private final TransferReviewProperties review;
  private final JsonMapper json;
  private final TransferEmails emails;

  public TransferService(
      WalletLookupService recipients,
      WalletTransferService wallets,
      TransferIdempotency idempotency,
      RiskAssessmentService risk,
      TransferRepository transfers,
      AuditService audit,
      TransferReviewProperties review,
      JsonMapper json,
      TransferEmails emails) {
    this.recipients = Objects.requireNonNull(recipients, "recipients");
    this.wallets = Objects.requireNonNull(wallets, "wallets");
    this.idempotency = Objects.requireNonNull(idempotency, "idempotency");
    this.risk = Objects.requireNonNull(risk, "risk");
    this.transfers = Objects.requireNonNull(transfers, "transfers");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.review = Objects.requireNonNull(review, "review");
    this.json = Objects.requireNonNull(json, "json");
    this.emails = Objects.requireNonNull(emails, "emails");
  }

  /**
   * Runs one {@code POST /transfers} in the order described on the class.
   *
   * @return the new transfer ({@code COMPLETED} or {@code PENDING_REVIEW}), or the replayed one
   * @throws ApiException every refusal of the class description
   */
  public TransferView create(TransferCommand command) {
    Objects.requireNonNull(command, "command");
    UUID sender = command.senderId();
    // Q3 step 1 (the rest of it): amount, then currency.
    Money amount = requestedAmount(command.amount(), command.currency());
    // Q3 steps 2 and 3: KYC, then the recipient, counted in the lookup windows (Q5).
    TransferRecipient recipient =
        recipients.resolveTransferRecipient(
            sender, command.receiverWalletNumber(), command.remoteAddress());
    if (recipient.customerId().equals(sender)) {
      // resolveTransferRecipient already refuses the caller's own wallet.
      throw new IllegalStateException("recipient resolution returned the sender's wallet");
    }
    InetAddress clientIp = ClientAddresses.parse(command.remoteAddress());
    TransferIdempotentRequest request =
        new TransferIdempotentRequest(
            command.idempotencyKey(),
            TransferRequestFingerprint.builder(sender, command.receiverWalletNumber(), amount)
                .description(command.description())
                .build());

    // Q3 step 4: a repeated request is answered before the pre-check and the assessment.
    Optional<IdempotentResult> replayed = idempotency.replayIfCompleted(request);
    if (replayed.isPresent()) {
      return read(replayed.get());
    }
    // Q14 for transfers: the key's record is gone, but its transfer is not.
    Optional<TransferRow> bound = transfers.findBySenderAndKey(sender, command.idempotencyKey());
    if (bound.isPresent()) {
      return answerBound(bound.get(), sender, amount, recipient);
    }
    try {
      return run(command, amount, recipient, clientIp, request);
    } catch (KeyBoundToTransfer found) {
      // Found inside a transaction of the engine, which has rolled back: nothing was written.
      return answerBound(found.row, sender, amount, recipient);
    }
  }

  /** Q3 steps 5 to 7, for a key that no transfer of this sender uses. */
  private TransferView run(
      TransferCommand command,
      Money amount,
      TransferRecipient recipient,
      InetAddress clientIp,
      TransferIdempotentRequest request) {
    UUID sender = command.senderId();
    UUID key = command.idempotencyKey();

    // Q3 step 5: the pre-check; a storable refusal is stored for the key like one under the locks.
    TransferPrecheck precheck;
    try {
      precheck = wallets.precheck(sender, amount);
    } catch (ApiException refusal) {
      if (!idempotency.storedRejections().contains(refusal.errorCode())) {
        // The minimum (INVALID_AMOUNT) does not consume the key (doc 02 §2.4).
        throw refusal;
      }
      UUID senderWallet = wallets.walletIdOf(sender);
      return read(
          idempotency.refuse(
              request,
              refusal,
              stored -> {
                requireUnboundKey(sender, key);
                audit.record(
                    TransferAudit.refused(
                        sender, null, senderWallet, amount, stored.errorCode(), clientIp));
              }));
    }

    // Q3 step 6: the risk assessment, in its own transaction, for the id generated here.
    Attempt attempt =
        new Attempt(
            UUID.randomUUID(), newReference(), command, amount, precheck, recipient, clientIp);
    RiskAssessmentResult assessment = assess(attempt);

    // Q3 step 7: the money transaction, or the stored BLOCK.
    if (assessment.decision() == AssessmentDecision.BLOCK) {
      return read(
          idempotency.refuse(
              request,
              new ApiException(ErrorCode.RISK_BLOCKED, RISK_BLOCKED),
              stored -> recordBlocked(attempt, assessment)));
    }
    return read(
        idempotency.execute(
            request,
            () -> move(attempt, assessment),
            stored -> {
              requireUnboundKey(sender, key);
              audit.record(
                  TransferAudit.refused(
                      sender,
                      attempt.transferId(),
                      precheck.sender().walletId(),
                      amount,
                      stored.errorCode(),
                      clientIp));
            }));
  }

  // Q14 for transfers ---------------------------------------------------------------------------

  /**
   * The answer for a key that {@code row}, a transfer of {@code sender}, already uses: its creation
   * response if the request is the same transfer, otherwise {@code 409 IDEMPOTENCY_KEY_REUSED}.
   * Reads only; nothing is assessed, written or audited.
   */
  private TransferView answerBound(
      TransferRow row, UUID sender, Money amount, TransferRecipient recipient) {
    TransferIdempotency.requireSameTransfer(
        new TransferIdentity(sender, wallets.walletIdOf(sender), recipient.walletId(), amount),
        new TransferIdentity(
            row.senderCustomerId(),
            row.senderWalletId(),
            row.receiverWalletId(),
            Money.of(row.amount(), Currency.getInstance(row.currency()))));
    if (row.heldAt() == null && TransferStatus.BLOCKED.name().equals(row.status())) {
      // The stored refusal of a BLOCK, with the same message (Q3; MVP-ADR-06).
      throw new ApiException(ErrorCode.RISK_BLOCKED, RISK_BLOCKED);
    }
    return TransferViews.asCreated(
        row, sender, new CounterpartyView(recipient.maskedName(), recipient.maskedWalletNumber()));
  }

  /**
   * The first step inside every transaction the engine opens for this request, before any lock or
   * write: if a transfer with this key committed after the early check, the transaction ends here
   * and rolls back, and {@link #create} answers from that transfer.
   */
  private void requireUnboundKey(UUID sender, UUID key) {
    Optional<TransferRow> row = transfers.findBySenderAndKey(sender, key);
    if (row.isPresent()) {
      throw new KeyBoundToTransfer(row.get());
    }
  }

  // Q3 step 6 -----------------------------------------------------------------------------------

  private RiskAssessmentResult assess(Attempt attempt) {
    UUID sender = attempt.command().senderId();
    try {
      boolean knownRecipient =
          transfers.completedTransferExists(sender, attempt.recipient().customerId());
      return risk.assess(
          new RiskAssessmentRequest(
              attempt.transferId(),
              sender,
              attempt.amount(),
              attempt.precheck().sender().createdAt(),
              knownRecipient,
              attempt.clientIp()));
    } catch (Throwable failure) {
      // Any failure, not only an ApiException (doc 08 §5, step 3 notes): no transfer row, no hold,
      // nothing stored for the key, and an audit row of its own, because the risk transaction has
      // rolled back. Never treated as score 0.
      log.error("Transfer refused: the risk assessment failed ({})", failure.getClass().getName());
      recordRiskFailure(attempt);
      throw new ApiException(ErrorCode.SYSTEM_ERROR, SYSTEM_ERROR);
    }
  }

  private void recordRiskFailure(Attempt attempt) {
    try {
      audit.recordIndependently(
          TransferAudit.refused(
              attempt.command().senderId(),
              attempt.transferId(),
              attempt.precheck().sender().walletId(),
              attempt.amount(),
              ErrorCode.SYSTEM_ERROR,
              attempt.clientIp()));
    } catch (RuntimeException e) {
      log.error("Could not audit a failed risk assessment ({})", e.getClass().getName());
    }
  }

  // Q3 step 7 -----------------------------------------------------------------------------------

  /** Inside the money transaction, after the idempotency row: locks, re-checks, moves, records. */
  private StoredResponse move(Attempt attempt, RiskAssessmentResult assessment) {
    requireUnboundKey(attempt.command().senderId(), attempt.command().idempotencyKey());
    LockedTransferWallets locked =
        wallets.lock(attempt.precheck().sender().walletId(), attempt.recipient().walletId());
    // doc 08 §5, step 4 notes: the locked wallets are the JWT subject's and the resolved one.
    if (!locked.sender().customerId().equals(attempt.command().senderId())
        || !locked.recipient().customerId().equals(attempt.recipient().customerId())) {
      throw new IllegalStateException("locked wallets do not belong to the transfer's parties");
    }
    TransferRow row;
    switch (assessment.decision()) {
      case ALLOW, MONITOR -> {
        TransferSettlement settlement =
            wallets.complete(locked, attempt.amount(), attempt.reference());
        row = insert(attempt, assessment, TransferStatus.COMPLETED, settlement.limitDate(), POSTED);
        audit.record(
            TransferAudit.created(row, settlement.ledgerTransactionId(), attempt.clientIp()));
        // Q9: TRANSFER_SENT to the sender, TRANSFER_RECEIVED to the receiver.
        emails.statusChanged(row);
      }
      case MANUAL_REVIEW -> {
        LocalDate limitDate = wallets.hold(locked, attempt.amount());
        row = insert(attempt, assessment, TransferStatus.PENDING_REVIEW, limitDate, null);
        audit.record(TransferAudit.created(row, null, attempt.clientIp()));
        // Q9: TRANSFER_HELD to the sender only; the receiver hears nothing before completion.
        emails.statusChanged(row);
      }
      case BLOCK -> throw new IllegalStateException("a blocked transfer moves no money");
      default -> throw new IllegalStateException("unknown risk decision");
    }
    return new StoredResponse(CREATED, json.writeValueAsString(view(row, attempt)));
  }

  /** In the transaction that stores {@code RISK_BLOCKED} for the key (Q3; MVP-ADR-06). */
  private void recordBlocked(Attempt attempt, RiskAssessmentResult assessment) {
    requireUnboundKey(attempt.command().senderId(), attempt.command().idempotencyKey());
    // Nothing moves, so the row keeps the pre-check's day (doc 08 §5, step 4 notes).
    TransferRow row =
        insert(attempt, assessment, TransferStatus.BLOCKED, attempt.precheck().limitDate(), null);
    audit.record(TransferAudit.blocked(row, attempt.clientIp()));
  }

  private TransferRow insert(
      Attempt attempt,
      RiskAssessmentResult assessment,
      TransferStatus status,
      LocalDate limitDate,
      String ledgerStatus) {
    // The domain's invariants (sender and receiver differ, amount positive, description length).
    TransferDetails details =
        new TransferDetails(
            attempt.transferId(),
            attempt.reference(),
            attempt.command().senderId(),
            attempt.recipient().customerId(),
            attempt.precheck().sender().walletId(),
            attempt.recipient().walletId(),
            attempt.amount(),
            attempt.command().description(),
            attempt.command().idempotencyKey(),
            limitDate,
            assessment.assessmentId());
    return transfers.insert(
        new NewTransfer(
            details.id(),
            details.reference(),
            details.senderCustomerId(),
            details.receiverCustomerId(),
            details.senderWalletId(),
            details.receiverWalletId(),
            details.amount().amount(),
            details.amount().currency().getCurrencyCode(),
            details.description(),
            status.name(),
            details.idempotencyKey(),
            details.limitDate(),
            details.riskAssessmentId(),
            ledgerStatus,
            CorrelationId.current().orElse(null)),
        review.durationSeconds());
  }

  private static TransferView view(TransferRow row, Attempt attempt) {
    return TransferViews.view(
        row,
        attempt.command().senderId(),
        new CounterpartyView(
            attempt.recipient().maskedName(), attempt.recipient().maskedWalletNumber()));
  }

  // Helpers --------------------------------------------------------------------------------------

  private TransferView read(IdempotentResult result) {
    return json.readValue(result.response().jsonBody(), TransferView.class);
  }

  /**
   * The amount first, then the currency (doc 02 §2.4 step 2, as for deposits and withdrawals):
   * within {@code NUMERIC(19,2)}, at most 2 decimals and never rounded (MVP-ADR-21), positive, all
   * {@code INVALID_AMOUNT}; then THB only ({@code CURRENCY_MISMATCH}). Messages name the field,
   * never the value.
   */
  static Money requestedAmount(BigDecimal amount, String currency) {
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

  /** {@code TRF-} and 32 hex digits: 36 characters, within V4's 40; unique (V4). */
  static String newReference() {
    return "TRF-" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
  }

  /**
   * Ends a transaction of the engine that found the key already used by a transfer. Not an {@link
   * ApiException}, so the engine never stores it; it carries no message or stack trace and is never
   * logged.
   */
  private static final class KeyBoundToTransfer extends RuntimeException {

    private final transient TransferRow row;

    KeyBoundToTransfer(TransferRow row) {
      super(null, null, false, false);
      this.row = row;
    }
  }

  /** One request between its assessment and its money transaction. */
  private record Attempt(
      UUID transferId,
      String reference,
      TransferCommand command,
      Money amount,
      TransferPrecheck precheck,
      TransferRecipient recipient,
      InetAddress clientIp) {

    @Override
    public String toString() {
      return "Attempt[]";
    }
  }
}
