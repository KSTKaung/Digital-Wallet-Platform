package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.wallet.domain.WalletNumber;
import java.security.SecureRandom;
import org.springframework.stereotype.Component;

/**
 * Draws wallet numbers from a {@link SecureRandom}, so they cannot be guessed (documentations/06
 * MVP-ADR-20). Uniqueness is the database's job ({@code uq_wallets_wallet_number}); {@link
 * WalletService} draws again on a clash. Thread-safe: {@code SecureRandom} is.
 */
@Component
public class WalletNumberGenerator {

  private final SecureRandom random = new SecureRandom();

  public WalletNumber next() {
    return WalletNumber.generate(random);
  }
}
