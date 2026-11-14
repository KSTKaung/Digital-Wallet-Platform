/**
 * Read-only transaction history, read from the ledger through its application service (doc 06
 * MVP-ADR-11 amendment). End-to-end tests: {@code transfer.web.TransactionHistoryIT} (test
 * sources).
 *
 * <p>Module of the modular application (documentations/06 MVP-ADR-01). Other modules may use only
 * its {@code application} package; ArchUnit enforces this.
 */
package com.portfolio.wallet.transaction;
