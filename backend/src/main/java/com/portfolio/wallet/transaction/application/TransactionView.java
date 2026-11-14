package com.portfolio.wallet.transaction.application;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One item of {@code GET /transactions} (documentations/02 §2.4 "MVP history"; doc 06 MVP-ADR-11
 * amendment): exactly these fields, never a counterparty or an internal id.
 *
 * @param reference the server-generated reference of the deposit, withdrawal or transfer
 * @param type {@code DEPOSIT}, {@code WITHDRAWAL}, {@code TRANSFER_IN} or {@code TRANSFER_OUT}
 * @param amount positive, scale 2 (MVP-ADR-21)
 * @param postedAt the ledger posting's database time
 * @param ledgerStatus always {@code POSTED}: the history shows committed postings only
 */
public record TransactionView(
    String reference,
    String type,
    BigDecimal amount,
    String currency,
    Instant postedAt,
    String ledgerStatus) {}
