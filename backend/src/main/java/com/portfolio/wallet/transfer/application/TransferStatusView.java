package com.portfolio.wallet.transfer.application;

/**
 * {@code GET /transfers/{id}/status} (documentations/02 §2.4: {@code {status, ledgerStatus}}).
 *
 * @param ledgerStatus {@code POSTED} for {@code COMPLETED}, null otherwise (MVP-ADR-03)
 */
public record TransferStatusView(String status, String ledgerStatus) {}
