package com.portfolio.wallet.wallet.application;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration of the wallet module.
 *
 * @param walletNumberAttempts how many random wallet numbers an approval draws before it gives up
 *     because every one was taken. A clash is very unlikely (10 random digits, documentations/06
 *     MVP-ADR-20), so this only bounds the loop; it is not a documented value
 */
@Validated
@ConfigurationProperties(prefix = "app.wallet")
public record WalletProperties(@Min(1) int walletNumberAttempts) {}
