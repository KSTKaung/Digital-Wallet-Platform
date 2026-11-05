package com.portfolio.wallet.ledger.persistence;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes postings to {@code ledger.financial_transactions} and {@code ledger.ledger_entries} (V2),
 * and reads the accounts they need. The database sets {@code created_at} and {@code created_xid},
 * checks at commit that each transaction is balanced, refuses entries on a non-{@code ACTIVE}
 * account and locks a customer account {@code FOR SHARE} while an entry is added (V2 triggers).
 */
@Repository
public class LedgerPostingRepository {

  private static final String ACCOUNT_COLUMNS =
      "SELECT id, code, account_type, owner_reference, currency, status FROM ledger.ledger_accounts";

  private final JdbcClient jdbc;

  public LedgerPostingRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * A wallet's {@code CUSTOMER} account, without a lock: the entry trigger takes {@code FOR SHARE}
   * when the entry is added, after the caller has locked the wallet (doc 07 §8 lock order).
   */
  public Optional<LedgerAccountRow> findCustomerAccount(UUID walletId) {
    return jdbc.sql(
            ACCOUNT_COLUMNS + " WHERE owner_reference = :owner AND account_type = 'CUSTOMER'")
        .param("owner", walletId)
        .query(LedgerPostingRepository::map)
        .optional();
  }

  /** A seeded system or suspense account by code, without a lock (V2 seed). */
  public Optional<LedgerAccountRow> findByCode(String code) {
    return jdbc.sql(ACCOUNT_COLUMNS + " WHERE code = :code")
        .param("code", code)
        .query(LedgerPostingRepository::map)
        .optional();
  }

  public void insertTransaction(
      UUID id, String reference, String type, String currency, String correlationId) {
    jdbc.sql(
            """
            INSERT INTO ledger.financial_transactions
              (id, transaction_reference, transaction_type, currency, correlation_id)
            VALUES (:id, :reference, :type, :currency, :correlation)
            """)
        .param("id", id)
        .param("reference", reference)
        .param("type", type)
        .param("currency", currency)
        .param("correlation", correlationId, java.sql.Types.VARCHAR)
        .update();
  }

  public void insertEntry(
      UUID transactionId, UUID accountId, String entryType, BigDecimal amount, String currency) {
    jdbc.sql(
            """
            INSERT INTO ledger.ledger_entries
              (transaction_id, account_id, entry_type, amount, currency)
            VALUES (:transaction, :account, :type, :amount, :currency)
            """)
        .param("transaction", transactionId)
        .param("account", accountId)
        .param("type", entryType)
        .param("amount", amount)
        .param("currency", currency)
        .update();
  }

  private static LedgerAccountRow map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new LedgerAccountRow(
        rs.getObject("id", UUID.class),
        rs.getString("code"),
        rs.getString("account_type"),
        rs.getObject("owner_reference", UUID.class),
        rs.getString("currency"),
        rs.getString("status"));
  }
}
