package com.portfolio.wallet.ledger.persistence;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One entry on a customer account with its financial transaction's reference and type (V2 {@code
 * ledger.ledger_entries} joined to {@code ledger.financial_transactions}).
 *
 * @param createdAt the entry's {@code created_at}, set by the database to the posting transaction's
 *     start ({@code now()}), the same value as its transaction's {@code created_at}
 */
public record LedgerHistoryRow(
    String reference,
    String transactionType,
    String entryType,
    BigDecimal amount,
    String currency,
    Instant createdAt) {}
