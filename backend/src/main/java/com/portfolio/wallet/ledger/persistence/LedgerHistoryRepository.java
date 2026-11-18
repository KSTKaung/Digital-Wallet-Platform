package com.portfolio.wallet.ledger.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads a customer account's entries for the history (documentations/06 MVP-ADR-11 amendment; doc
 * 07 D06). Read-only and without locks. The page is served by V2's {@code
 * ix_ledger_entries_account_created (account_id, created_at, id)}, scanned backwards for {@code
 * ORDER BY created_at DESC, id DESC}; the count by the same index.
 */
@Repository
public class LedgerHistoryRepository {

  private final JdbcClient jdbc;

  public LedgerHistoryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** The id of a wallet's {@code CUSTOMER} account, without a lock. */
  public Optional<UUID> findCustomerAccountId(UUID walletId) {
    return jdbc.sql(
            """
            SELECT id FROM ledger.ledger_accounts
            WHERE owner_reference = :owner AND account_type = 'CUSTOMER'
            """)
        .param("owner", walletId)
        .query(UUID.class)
        .optional();
  }

  /** How many entries the account has. */
  public long countEntries(UUID accountId) {
    return jdbc.sql("SELECT count(*) FROM ledger.ledger_entries WHERE account_id = :account")
        .param("account", accountId)
        .query(Long.class)
        .single();
  }

  /**
   * One page of the account's entries, newest first; entries posted by the same database
   * transaction share {@code created_at}, so the entry id breaks the tie (PostgreSQL's uuid order).
   */
  public List<LedgerHistoryRow> pageEntries(UUID accountId, int limit, long offset) {
    return jdbc.sql(
            """
            SELECT t.transaction_reference, t.transaction_type, e.entry_type, e.amount,
                   e.currency, e.created_at
            FROM ledger.ledger_entries e
            JOIN ledger.financial_transactions t ON t.id = e.transaction_id
            WHERE e.account_id = :account
            ORDER BY e.created_at DESC, e.id DESC
            LIMIT :limit OFFSET :offset
            """)
        .param("account", accountId)
        .param("limit", limit)
        .param("offset", offset)
        .query(LedgerHistoryRepository::map)
        .list();
  }

  private static LedgerHistoryRow map(ResultSet rs, int row) throws SQLException {
    return new LedgerHistoryRow(
        rs.getString("transaction_reference"),
        rs.getString("transaction_type"),
        rs.getString("entry_type"),
        rs.getBigDecimal("amount"),
        rs.getString("currency"),
        rs.getObject("created_at", OffsetDateTime.class).toInstant());
  }
}
