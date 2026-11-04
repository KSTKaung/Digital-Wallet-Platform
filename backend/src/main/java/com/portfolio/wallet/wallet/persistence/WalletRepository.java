package com.portfolio.wallet.wallet.persistence;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code wallet.wallets} (V3). Plain SQL with JdbcClient (documentations/08 §4B.2 Q4): the database
 * sets {@code created_at}, {@code updated_at} and {@code version}, so none of them is ever written
 * here.
 */
@Repository
public class WalletRepository {

  private final JdbcClient jdbc;

  public WalletRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a wallet unless its number is taken. Only a wallet-number clash is absorbed (empty
   * result, so the caller draws another number); a second wallet for the same customer still raises
   * the {@code uq_wallets_customer} unique violation, which the caller maps.
   *
   * @return the id, or empty if the number is already in use
   */
  public Optional<UUID> insertIfNumberFree(
      UUID id,
      String walletNumber,
      UUID customerId,
      String tier,
      String status,
      String currency,
      BigDecimal availableBalance,
      BigDecimal reservedBalance) {
    return jdbc.sql(
            """
            INSERT INTO wallet.wallets
              (id, wallet_number, customer_id, tier, status, currency, available_balance,
               reserved_balance)
            VALUES (:id, :number, :customer, :tier, :status, :currency, :available, :reserved)
            ON CONFLICT (wallet_number) DO NOTHING
            RETURNING id
            """)
        .param("id", id)
        .param("number", walletNumber)
        .param("customer", customerId)
        .param("tier", tier)
        .param("status", status)
        .param("currency", currency)
        .param("available", availableBalance)
        .param("reserved", reservedBalance)
        .query(UUID.class)
        .optional();
  }

  public boolean existsForCustomer(UUID customerId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM wallet.wallets WHERE customer_id = :customer)")
        .param("customer", customerId)
        .query(Boolean.class)
        .single();
  }

  /** The customer's wallet ({@code uq_wallets_customer}: at most one), as committed now. */
  public Optional<WalletRow> findByCustomer(UUID customerId) {
    return findByCustomer(customerId, "");
  }

  /**
   * Locks the customer's wallet: the first lock of a status change, as of every money flow on one
   * wallet (doc 07 §8). One statement, one row, so there is no order to get wrong.
   */
  public Optional<WalletRow> findByCustomerForUpdate(UUID customerId) {
    return findByCustomer(customerId, " FOR UPDATE");
  }

  // The suffix is one of this class's constants, never client input.
  private Optional<WalletRow> findByCustomer(UUID customerId, String lockClause) {
    return jdbc.sql(
            """
            SELECT id, wallet_number, customer_id, tier, status, currency, available_balance,
                   reserved_balance, version, created_at, updated_at
            FROM wallet.wallets WHERE customer_id = :customer
            """
                + lockClause)
        .param("customer", customerId)
        .query(
            (rs, row) ->
                new WalletRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("wallet_number"),
                    rs.getObject("customer_id", UUID.class),
                    rs.getString("tier"),
                    rs.getString("status"),
                    rs.getString("currency"),
                    rs.getBigDecimal("available_balance"),
                    rs.getBigDecimal("reserved_balance"),
                    rs.getLong("version"),
                    Timestamps.read(rs, "created_at"),
                    Timestamps.read(rs, "updated_at")))
        .optional();
  }

  /**
   * Sets the status with the optimistic {@code version} check (MVP-ADR-08); V3's trigger sets
   * {@code updated_at} and keeps {@code CLOSED} final.
   *
   * @return 1, or 0 if the version is no longer {@code expectedVersion}
   */
  public int updateStatus(UUID id, String status, long expectedVersion) {
    return jdbc.sql(
            """
            UPDATE wallet.wallets SET status = :status, version = version + 1
            WHERE id = :id AND version = :version
            """)
        .param("status", status)
        .param("id", id)
        .param("version", expectedVersion)
        .update();
  }
}
