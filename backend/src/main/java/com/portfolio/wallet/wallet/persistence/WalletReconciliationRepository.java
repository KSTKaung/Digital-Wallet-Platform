package com.portfolio.wallet.wallet.persistence;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The wallet module's reads for the scheduled reconciliation (documentations/06 MVP-ADR-03 checks
 * 1–4; doc 07 §5). Read-only, without locks ({@code FOR UPDATE} would make the reconciliation wait
 * on, and block, money transactions), wallet schema only.
 */
@Repository
public class WalletReconciliationRepository {

  private final JdbcClient jdbc;

  public WalletReconciliationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /** A wallet's balance snapshot. */
  public record BalanceRow(UUID walletId, BigDecimal available, BigDecimal reserved) {}

  /** A completed deposit or withdrawal: its id and the reference it was posted under. */
  public record MovementRow(UUID id, UUID walletId, String reference, BigDecimal amount) {}

  /** A daily-limit usage row with a non-zero reservation. */
  public record ReservedUsageRow(
      UUID walletId, String operation, LocalDate limitDate, BigDecimal reserved) {}

  /** Every wallet's balances (full scan of {@code wallet.wallets}). */
  public List<BalanceRow> balances() {
    return jdbc.sql("SELECT id, available_balance, reserved_balance FROM wallet.wallets")
        .query(
            (rs, row) ->
                new BalanceRow(
                    rs.getObject("id", UUID.class),
                    rs.getBigDecimal("available_balance"),
                    rs.getBigDecimal("reserved_balance")))
        .list();
  }

  /** Every {@code COMPLETED} deposit (V3 allows no other status). */
  public List<MovementRow> completedDeposits() {
    return jdbc.sql(
            "SELECT id, wallet_id, reference, amount FROM wallet.deposits WHERE status = 'COMPLETED'")
        .query(
            (rs, row) ->
                new MovementRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("wallet_id", UUID.class),
                    rs.getString("reference"),
                    rs.getBigDecimal("amount")))
        .list();
  }

  /** Every {@code COMPLETED} withdrawal (V3 allows no other status). */
  public List<MovementRow> completedWithdrawals() {
    return jdbc.sql(
            "SELECT id, wallet_id, reference, amount FROM wallet.withdrawals WHERE status = 'COMPLETED'")
        .query(
            (rs, row) ->
                new MovementRow(
                    rs.getObject("id", UUID.class),
                    rs.getObject("wallet_id", UUID.class),
                    rs.getString("reference"),
                    rs.getBigDecimal("amount")))
        .list();
  }

  /**
   * The usage rows of any operation whose {@code reserved} is not zero, by their <b>stored</b>
   * {@code limit_date} (MVP-ADR-22 Y5). A row with nothing reserved needs no comparison unless a
   * hold expects a reservation there, which the caller detects from the holds themselves.
   */
  public List<ReservedUsageRow> reservedUsage() {
    return jdbc.sql(
            """
            SELECT wallet_id, operation, limit_date, reserved
            FROM wallet.daily_limit_usage WHERE reserved <> 0
            """)
        .query(
            (rs, row) ->
                new ReservedUsageRow(
                    rs.getObject("wallet_id", UUID.class),
                    rs.getString("operation"),
                    rs.getObject("limit_date", LocalDate.class),
                    rs.getBigDecimal("reserved")))
        .list();
  }
}
