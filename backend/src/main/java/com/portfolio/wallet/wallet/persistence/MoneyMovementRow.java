package com.portfolio.wallet.wallet.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One {@code wallet.deposits} or {@code wallet.withdrawals} row (V3), without the deposit's
 * free-text client reference.
 *
 * @param reference the server-generated unique reference, also the ledger {@code
 *     transaction_reference}
 * @param limitDate the Asia/Bangkok business day the daily limit was counted on (MVP-ADR-22)
 */
public record MoneyMovementRow(
    UUID id,
    UUID walletId,
    UUID customerId,
    BigDecimal amount,
    String currency,
    String status,
    UUID idempotencyKey,
    String reference,
    LocalDate limitDate,
    String ledgerStatus,
    String correlationId) {}
