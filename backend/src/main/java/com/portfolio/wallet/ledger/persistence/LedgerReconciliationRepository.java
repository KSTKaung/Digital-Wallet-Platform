package com.portfolio.wallet.ledger.persistence;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The ledger's reads for the scheduled reconciliation (documentations/06 MVP-ADR-03; doc 07 §5).
 * Read-only, without locks, ledger schema only. Each statement scans its tables in full: the
 * reconciliation compares everything, every run (cost in {@code ReconciliationService}).
 */
@Repository
public class LedgerReconciliationRepository {

  private final JdbcClient jdbc;

  public LedgerReconciliationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * An account's entry totals; {@code ownerReference} is the wallet id of a customer account,
   * {@code code} the code of a system or suspense account.
   */
  public record AccountTotalsRow(
      UUID accountId,
      String accountType,
      UUID ownerReference,
      String code,
      BigDecimal debits,
      BigDecimal credits) {}

  /**
   * A ledger transaction with its entry totals: the number of entries, Σ DEBIT, Σ CREDIT, and the
   * distinct accounts it debits and credits.
   */
  public record PostingRow(
      UUID id,
      String reference,
      String type,
      long entries,
      BigDecimal debits,
      BigDecimal credits,
      List<UUID> debitAccounts,
      List<UUID> creditAccounts) {}

  /**
   * Every account with its Σ DEBIT and Σ CREDIT (zero for an account without entries). Entries are
   * aggregated before the join, in one pass over {@code ledger_entries}.
   */
  public List<AccountTotalsRow> accountTotals() {
    return jdbc.sql(
            """
            WITH totals AS (
              SELECT account_id,
                     sum(amount) FILTER (WHERE entry_type = 'DEBIT') AS debits,
                     sum(amount) FILTER (WHERE entry_type = 'CREDIT') AS credits
              FROM ledger.ledger_entries
              GROUP BY account_id)
            SELECT a.id, a.account_type, a.owner_reference, a.code,
                   coalesce(t.debits, 0) AS debits, coalesce(t.credits, 0) AS credits
            FROM ledger.ledger_accounts a
            LEFT JOIN totals t ON t.account_id = a.id
            """)
        .query(
            (rs, row) ->
                new AccountTotalsRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("account_type"),
                    rs.getObject("owner_reference", UUID.class),
                    rs.getString("code"),
                    rs.getBigDecimal("debits"),
                    rs.getBigDecimal("credits")))
        .list();
  }

  /**
   * Every ledger transaction with its entry totals, one row per transaction, in one grouped pass
   * over {@code ledger_entries} (no index needed; a transaction without entries gets zeros and
   * empty account lists). Serves the per-transaction Σ check and the amount and accounts of check
   * 2.
   */
  public List<PostingRow> postings() {
    return jdbc.sql(
            """
            SELECT t.id, t.transaction_reference, t.transaction_type,
                   count(e.id) AS entries,
                   coalesce(sum(e.amount) FILTER (WHERE e.entry_type = 'DEBIT'), 0) AS debits,
                   coalesce(sum(e.amount) FILTER (WHERE e.entry_type = 'CREDIT'), 0) AS credits,
                   coalesce(array_agg(DISTINCT e.account_id) FILTER (WHERE e.entry_type = 'DEBIT'),
                            '{}'::uuid[]) AS debit_accounts,
                   coalesce(array_agg(DISTINCT e.account_id) FILTER (WHERE e.entry_type = 'CREDIT'),
                            '{}'::uuid[]) AS credit_accounts
            FROM ledger.financial_transactions t
            LEFT JOIN ledger.ledger_entries e ON e.transaction_id = t.id
            GROUP BY t.id, t.transaction_reference, t.transaction_type
            """)
        .query(
            (rs, row) ->
                new PostingRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("transaction_reference"),
                    rs.getString("transaction_type"),
                    rs.getLong("entries"),
                    rs.getBigDecimal("debits"),
                    rs.getBigDecimal("credits"),
                    uuids(rs.getArray("debit_accounts")),
                    uuids(rs.getArray("credit_accounts"))))
        .list();
  }

  private static List<UUID> uuids(Array array) throws SQLException {
    try {
      List<UUID> ids = new ArrayList<>();
      for (Object element : (Object[]) array.getArray()) {
        ids.add(element instanceof UUID id ? id : UUID.fromString(element.toString()));
      }
      return List.copyOf(ids);
    } finally {
      array.free();
    }
  }
}
