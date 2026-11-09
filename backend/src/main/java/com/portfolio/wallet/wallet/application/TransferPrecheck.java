package com.portfolio.wallet.wallet.application;

import java.time.LocalDate;
import java.util.Objects;

/**
 * A transfer that passed {@link WalletTransferService#precheck}.
 *
 * @param sender the sender wallet as committed when it was read; {@code createdAt} is the wallet
 *     age for the risk request
 * @param limitDate the sender's Asia/Bangkok business day at the pre-check, from the same {@code
 *     BusinessDay} and {@code Clock} as {@code hold} and {@code complete} (MVP-ADR-22). A transfer
 *     that ends {@code BLOCKED} moves no money, so this is the {@code limit_date} its row stores
 *     ({@code transfer.transfers.limit_date NOT NULL}, V4); a completed or held transfer stores the
 *     day returned under the locks instead
 */
public record TransferPrecheck(TransferWalletView sender, LocalDate limitDate) {

  public TransferPrecheck {
    Objects.requireNonNull(sender, "sender");
    Objects.requireNonNull(limitDate, "limitDate");
  }
}
