package com.portfolio.wallet.ledger.persistence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code ledger.ledger_accounts} (V2). Plain SQL, like the other modules (documentations/08 §4B.2
 * Q4). The runtime role may insert accounts and change only their status; {@code created_at} is set
 * by the database.
 */
@Repository
public class LedgerAccountRepository {

  private final JdbcClient jdbc;

  public LedgerAccountRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a customer account. Customer accounts have no code ({@code
   * ck_ledger_accounts_system_code}); {@code uq_ledger_accounts_customer_owner} allows one per
   * wallet.
   */
  public void insertCustomerAccount(UUID id, UUID walletId, String currency, String status) {
    jdbc.sql(
            """
            INSERT INTO ledger.ledger_accounts (id, account_type, owner_reference, currency, status)
            VALUES (:id, 'CUSTOMER', :owner, :currency, :status)
            """)
        .param("id", id)
        .param("owner", walletId)
        .param("currency", currency)
        .param("status", status)
        .update();
  }

  /**
   * Locks a wallet's {@code CUSTOMER} account for a status change. The caller has locked the wallet
   * first, the same order as a posting (doc 07 §8). Like every row lock this needs UPDATE on a
   * column, which V2 grants on {@code status}.
   */
  public Optional<LedgerAccountRow> findCustomerAccountForUpdate(UUID walletId) {
    return jdbc.sql(
            """
            SELECT id, code, account_type, owner_reference, currency, status
            FROM ledger.ledger_accounts
            WHERE owner_reference = :owner AND account_type = 'CUSTOMER'
            FOR UPDATE
            """)
        .param("owner", walletId)
        .query(
            (rs, row) ->
                new LedgerAccountRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("code"),
                    rs.getString("account_type"),
                    rs.getObject("owner_reference", UUID.class),
                    rs.getString("currency"),
                    rs.getString("status")))
        .optional();
  }

  /**
   * Changes an account's status if it is still {@code fromStatus}; V2's trigger keeps {@code
   * CLOSED} final.
   *
   * @return 1, or 0 if the status was not {@code fromStatus}
   */
  public int updateStatus(UUID id, String fromStatus, String toStatus) {
    return jdbc.sql(
            """
            UPDATE ledger.ledger_accounts SET status = :to
            WHERE id = :id AND status = :from
            """)
        .param("to", toStatus)
        .param("id", id)
        .param("from", fromStatus)
        .update();
  }
}
