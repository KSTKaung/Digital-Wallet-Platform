package com.portfolio.wallet.wallet.persistence;

import java.sql.Types;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code wallet.deposits} and {@code wallet.withdrawals} (V3): append-only, status {@code
 * COMPLETED} and {@code ledger_status = POSTED} only (doc 07 D10; MVP-ADR-03). The database sets
 * {@code created_at}. {@code UNIQUE (customer_id, idempotency_key)} keeps a retry from posting
 * twice after the idempotency record is purged (MVP-ADR-02 amendment).
 */
@Repository
public class MoneyMovementRepository {

  /** The two tables; never client input. */
  public enum Table {
    DEPOSITS("wallet.deposits"),
    WITHDRAWALS("wallet.withdrawals");

    private final String name;

    Table(String name) {
      this.name = name;
    }
  }

  private final JdbcClient jdbc;

  public MoneyMovementRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * @param clientReference deposits only: the client's free-text note; must be null for a
   *     withdrawal, whose table has no such column
   */
  public void insert(Table table, MoneyMovementRow movement, String clientReference) {
    if (table == Table.WITHDRAWALS && clientReference != null) {
      throw new IllegalArgumentException("withdrawals have no client reference");
    }
    String noteColumn = table == Table.DEPOSITS ? ", client_reference" : "";
    String noteValue = table == Table.DEPOSITS ? ", :note" : "";
    JdbcClient.StatementSpec statement =
        jdbc.sql(
                "INSERT INTO "
                    + table.name
                    + " (id, wallet_id, customer_id, amount, currency, status, idempotency_key,"
                    + " reference, limit_date, ledger_status, correlation_id"
                    + noteColumn
                    + ") VALUES (:id, :wallet, :customer, :amount, :currency, :status, :key,"
                    + " :reference, :date, :ledgerStatus, :correlation"
                    + noteValue
                    + ")")
            .param("id", movement.id())
            .param("wallet", movement.walletId())
            .param("customer", movement.customerId())
            .param("amount", movement.amount())
            .param("currency", movement.currency())
            .param("status", movement.status())
            .param("key", movement.idempotencyKey())
            .param("reference", movement.reference())
            .param("date", movement.limitDate())
            .param("ledgerStatus", movement.ledgerStatus())
            .param("correlation", movement.correlationId(), Types.VARCHAR);
    if (table == Table.DEPOSITS) {
      statement = statement.param("note", clientReference, Types.VARCHAR);
    }
    statement.update();
  }

  /** The customer's movement stored with this idempotency key, if any. */
  public Optional<MoneyMovementRow> findByCustomerAndKey(
      Table table, UUID customerId, UUID idempotencyKey) {
    return jdbc.sql(
            "SELECT id, wallet_id, customer_id, amount, currency, status, idempotency_key,"
                + " reference, limit_date, ledger_status, correlation_id FROM "
                + table.name
                + " WHERE customer_id = :customer AND idempotency_key = :key")
        .param("customer", customerId)
        .param("key", idempotencyKey)
        .query(
            (rs, row) ->
                new MoneyMovementRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("wallet_id", UUID.class),
                    rs.getObject("customer_id", UUID.class),
                    rs.getBigDecimal("amount"),
                    rs.getString("currency"),
                    rs.getString("status"),
                    rs.getObject("idempotency_key", UUID.class),
                    rs.getString("reference"),
                    rs.getObject("limit_date", LocalDate.class),
                    rs.getString("ledger_status"),
                    rs.getString("correlation_id")))
        .optional();
  }
}
