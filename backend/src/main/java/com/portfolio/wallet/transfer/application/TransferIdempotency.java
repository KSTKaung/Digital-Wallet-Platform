package com.portfolio.wallet.transfer.application;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.idempotency.IdempotencyEngine;
import com.portfolio.wallet.common.idempotency.IdempotentResult;
import com.portfolio.wallet.common.idempotency.StoredResponse;
import com.portfolio.wallet.transfer.persistence.TransferIdempotencyRecordRepository;
import java.time.Clock;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * {@code Idempotency-Key} handling for {@code POST /transfers} (documentations/02 §1.4; doc 06
 * MVP-ADR-06 "Transfers and one engine"; doc 08 §4C.2 Q2, Q3, Q6, §4C.4 item 9).
 *
 * <p>The transfer module's engine: {@link IdempotencyEngine} (flow, rejections, expiry, lock order;
 * see its documentation) over the transfer module's own store, {@link
 * TransferIdempotencyRecordRepository} on {@code transfer.idempotency_records}, with the transfer
 * TTL ({@link TransferIdempotencyProperties}) and the transfer's stored rejections, {@link
 * #STORED_REJECTIONS}. It never reads or writes the wallet module's records.
 *
 * <p>Order of use in {@code POST /transfers} (Q3): after the request, KYC and recipient checks,
 * which do not consume the key, {@link #replayIfCompleted} answers a repeated request before the
 * pre-check and the risk assessment; then {@link #execute} runs the money transaction, the
 * authority under the row lock. {@code SYSTEM_ERROR} (a risk failure, a lock or statement timeout,
 * Q6) and every code outside the set are never stored, so a retry with the same key runs fresh.
 *
 * <p>Nothing here is logged: the key, the hash and the bodies stay out of the logs.
 */
@Service
public class TransferIdempotency {

  /**
   * Business-rule rejections that are stored and replayed (doc 02 §1.4; doc 06 MVP-ADR-06; Q3): the
   * four of deposits and withdrawals, and {@code RISK_BLOCKED}, whose {@code BLOCKED} transfer row
   * the rejection hook commits with the record.
   */
  static final Set<ErrorCode> STORED_REJECTIONS =
      EnumSet.of(
          ErrorCode.INSUFFICIENT_BALANCE,
          ErrorCode.SINGLE_LIMIT_EXCEEDED,
          ErrorCode.DAILY_LIMIT_EXCEEDED,
          ErrorCode.WALLET_FROZEN,
          ErrorCode.RISK_BLOCKED);

  private final IdempotencyEngine engine;

  public TransferIdempotency(
      TransferIdempotencyRecordRepository records,
      TransferIdempotencyProperties properties,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.engine =
        new IdempotencyEngine(
            records,
            properties.ttl(),
            STORED_REJECTIONS,
            clock,
            transactionManager,
            // No per-transaction settings yet: PR 5a step 4 sets the D14 lock and statement
            // timeouts here (doc 06 "Lock and statement timeouts").
            () -> {});
  }

  /**
   * Runs {@code transfer} at most once per key and request ({@link IdempotencyEngine#execute}).
   *
   * @param transfer the money transaction's work; it runs inside the transaction that holds the
   *     idempotency row, takes its own locks after it and returns the success response. It signals
   *     a business rejection by throwing an {@link ApiException} with a code in {@link
   *     #STORED_REJECTIONS}
   * @throws ApiException {@code IDEMPOTENCY_KEY_REUSED}, a fresh or replayed stored rejection, or
   *     whatever else the transfer throws
   * @throws IllegalStateException if called inside a transaction
   */
  public IdempotentResult execute(
      TransferIdempotentRequest request, Supplier<StoredResponse> transfer) {
    return execute(request, transfer, refusal -> {});
  }

  /**
   * As {@link #execute(TransferIdempotentRequest, Supplier)}, and {@code onRefusalStored} runs
   * inside the transaction that stores a business rejection for this request, after the record is
   * written, and only when this request's rejection is what got stored (for example the {@code
   * BLOCKED} row and audit row of a {@code RISK_BLOCKED}). If it throws, nothing is stored.
   */
  public IdempotentResult execute(
      TransferIdempotentRequest request,
      Supplier<StoredResponse> transfer,
      Consumer<ApiException> onRefusalStored) {
    Objects.requireNonNull(request, "request");
    return engine.execute(request.toEngineRequest(), transfer, onRefusalStored);
  }

  /**
   * The early replay of Q3 step 4 ({@link IdempotencyEngine#replayIfCompleted}): outside any
   * transaction and without a lock, answers a request whose key already has a committed, retained
   * outcome.
   *
   * @return the replayed success; empty if the request must run
   * @throws ApiException {@code IDEMPOTENCY_KEY_REUSED} or the replayed stored rejection
   * @throws IllegalStateException if called inside a transaction
   */
  public Optional<IdempotentResult> replayIfCompleted(TransferIdempotentRequest request) {
    Objects.requireNonNull(request, "request");
    return engine.replayIfCompleted(request.toEngineRequest());
  }

  /** The business rejections that are stored and replayed for transfers. */
  public Set<ErrorCode> storedRejections() {
    return engine.storedRejections();
  }
}
