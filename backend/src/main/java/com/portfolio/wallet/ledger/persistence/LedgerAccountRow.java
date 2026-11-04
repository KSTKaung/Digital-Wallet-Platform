package com.portfolio.wallet.ledger.persistence;

import java.util.UUID;

/** One {@code ledger.ledger_accounts} row as stored (V2). */
public record LedgerAccountRow(
    UUID id,
    String code,
    String accountType,
    UUID ownerReference,
    String currency,
    String status) {}
