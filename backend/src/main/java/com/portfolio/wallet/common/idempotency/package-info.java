/**
 * The {@code Idempotency-Key} engine shared by the money modules (documentations/02 §1.4; doc 06
 * MVP-ADR-06 "Transfers and one engine"; doc 08 §4C.2 Q2).
 *
 * <p><b>Shared code, not shared records.</b> {@link
 * com.portfolio.wallet.common.idempotency.IdempotencyEngine} holds the decision logic only. Each
 * money module implements {@link com.portfolio.wallet.common.idempotency.IdempotencyStore} in its
 * own {@code persistence} package over its own table ({@code wallet.idempotency_records}, {@code
 * transfer.idempotency_records}) and builds its own engine with its own TTL and its own set of
 * stored rejection codes. No SQL and no table belongs to this package, and it depends on no module.
 */
package com.portfolio.wallet.common.idempotency;
