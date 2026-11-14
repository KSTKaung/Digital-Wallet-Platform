package com.portfolio.wallet.ledger.application;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One posted entry on a customer account, as its customer sees it (documentations/06 MVP-ADR-11
 * amendment). Nothing else of the posting leaves the ledger: no ids, no counterparty account, no
 * correlation id.
 *
 * @param reference the financial transaction's {@code transaction_reference}, the business record's
 *     server-generated reference
 * @param movement {@code DEPOSIT}, {@code WITHDRAWAL}, {@code TRANSFER_IN} or {@code TRANSFER_OUT}
 *     ({@link com.portfolio.wallet.ledger.domain.AccountMovement})
 * @param amount positive, scale 2 (MVP-ADR-21)
 * @param postedAt the entry's database time: the start of the transaction that posted it
 */
public record LedgerHistoryItem(
    String reference, String movement, BigDecimal amount, String currency, Instant postedAt) {}
