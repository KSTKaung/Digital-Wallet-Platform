package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.wallet.persistence.WalletRepository;
import com.portfolio.wallet.wallet.persistence.WalletRow;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The counterparty of a customer's transfer as {@code GET /transfers} shows it (documentations/06
 * MVP-ADR-24 amendment "Visibility (Q8)"; doc 08 §4C.2 Q8): the masked legal name and the masked
 * wallet number, exactly as the recipient lookup masks them (MVP-ADR-20, {@link
 * WalletLookupService#masked}), and nothing else. The wallet's status does not matter: a transfer
 * keeps its counterparty after a freeze. The full legal name never enters the wallet module ({@link
 * RecipientNames}).
 *
 * <p>Read-only and outside any money transaction, so no name is looked up under money locks.
 */
@Service
public class TransferCounterparties {

  private final WalletRepository wallets;
  private final RecipientNames names;

  public TransferCounterparties(WalletRepository wallets, RecipientNames names) {
    this.wallets = Objects.requireNonNull(wallets, "wallets");
    this.names = Objects.requireNonNull(names, "names");
  }

  /**
   * @param walletIds the counterparties' wallet ids
   * @return one view per id found; the masked name is null if the customer has no legal name (never
   *     the case for an approved wallet, V8)
   * @throws IllegalStateException if a wallet does not exist (wallets are never deleted)
   */
  @Transactional(propagation = Propagation.NEVER, readOnly = true)
  public Map<UUID, WalletLookupView> of(Collection<UUID> walletIds) {
    Objects.requireNonNull(walletIds, "walletIds");
    Map<UUID, WalletLookupView> views = new LinkedHashMap<>();
    if (walletIds.isEmpty()) {
      return views;
    }
    Map<UUID, Optional<String>> namesByCustomer = new HashMap<>();
    for (WalletRow row : wallets.findByIds(walletIds)) {
      String name =
          namesByCustomer.computeIfAbsent(row.customerId(), names::maskedLegalName).orElse(null);
      views.put(
          row.id(), new WalletLookupView(name, WalletLookupService.masked(row.walletNumber())));
    }
    for (UUID id : walletIds) {
      if (!views.containsKey(id)) {
        throw new IllegalStateException("a transfer's counterparty wallet does not exist");
      }
    }
    return views;
  }
}
