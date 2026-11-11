package com.portfolio.wallet.wallet.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
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

  private static final String SELECT =
      """
      SELECT id, wallet_number, customer_id, tier, status, currency, available_balance,
             reserved_balance, version, created_at, updated_at
      FROM wallet.wallets WHERE
      """;

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

  /** The wallet with exactly this number ({@code uq_wallets_wallet_number}), as committed now. */
  public Optional<WalletRow> findByWalletNumber(String walletNumber) {
    return find("wallet_number = :value", walletNumber, "");
  }

  /**
   * The wallets with these ids, as committed now, in no particular order; missing ids are left out.
   */
  public List<WalletRow> findByIds(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return jdbc.sql(SELECT + "id IN (:ids)")
        .param("ids", List.copyOf(ids))
        .query(WalletRepository::map)
        .list();
  }

  /** The customer's wallet ({@code uq_wallets_customer}: at most one), as committed now. */
  public Optional<WalletRow> findByCustomer(UUID customerId) {
    return find("customer_id = :value", customerId, "");
  }

  /**
   * Locks the customer's wallet: the first lock of a status change, as of every money flow on one
   * wallet (doc 07 §8). One statement, one row, so there is no order to get wrong.
   */
  public Optional<WalletRow> findByCustomerForUpdate(UUID customerId) {
    return find("customer_id = :value", customerId, " FOR UPDATE");
  }

  /**
   * Locks two wallets {@code FOR UPDATE} in <b>one statement</b>, in ascending id order as
   * PostgreSQL compares uuids (doc 07 §8 lock order step 3; MVP-ADR-08). {@code ORDER BY} is
   * applied before the locking clause, so the rows are locked in that order, and every money flow
   * on two wallets therefore locks them in the same order: opposite-direction transfers cannot
   * deadlock. Sorting the ids in Java could disagree with the database's uuid order.
   *
   * @return the rows found, in ascending id order: two, or fewer if an id does not exist
   */
  public List<WalletRow> lockBothForUpdate(UUID first, UUID second) {
    return jdbc.sql(SELECT + "id IN (:first, :second) ORDER BY id FOR UPDATE")
        .param("first", first)
        .param("second", second)
        .query(WalletRepository::map)
        .list();
  }

  /**
   * Sets both balances with the optimistic {@code version} check (MVP-ADR-08): a transfer hold,
   * release or capture moves money between them. {@code ck_wallets_available_balance} and {@code
   * ck_wallets_reserved_balance} ({@code >= 0}) stay the last guards.
   *
   * @return 1, or 0 if the version is no longer {@code expectedVersion}
   */
  public int updateBalances(
      UUID id, BigDecimal availableBalance, BigDecimal reservedBalance, long expectedVersion) {
    return jdbc.sql(
            """
            UPDATE wallet.wallets
            SET available_balance = :available, reserved_balance = :reserved,
                version = version + 1
            WHERE id = :id AND version = :version
            """)
        .param("available", availableBalance)
        .param("reserved", reservedBalance)
        .param("id", id)
        .param("version", expectedVersion)
        .update();
  }

  // The condition and the suffix are this class's constants, never client input.
  private Optional<WalletRow> find(String condition, Object value, String lockClause) {
    return jdbc.sql(SELECT + condition + lockClause)
        .param("value", value)
        .query(WalletRepository::map)
        .optional();
  }

  private static WalletRow map(ResultSet rs, int row) throws SQLException {
    return new WalletRow(
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
        Timestamps.read(rs, "updated_at"));
  }

  /**
   * Sets the available balance with the optimistic {@code version} check (MVP-ADR-08). {@code
   * ck_wallets_available_balance} ({@code >= 0}) stays the last guard against an overdraft.
   *
   * @return 1, or 0 if the version is no longer {@code expectedVersion}
   */
  public int updateAvailableBalance(UUID id, BigDecimal availableBalance, long expectedVersion) {
    return jdbc.sql(
            """
            UPDATE wallet.wallets SET available_balance = :available, version = version + 1
            WHERE id = :id AND version = :version
            """)
        .param("available", availableBalance)
        .param("id", id)
        .param("version", expectedVersion)
        .update();
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
