package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.wallet.domain.LimitPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Binds {@link WalletProperties}, {@link LimitProperties} and {@link IdempotencyProperties}; builds
 * the {@link LimitPolicy}.
 */
@Configuration
@EnableConfigurationProperties({
  WalletProperties.class,
  LimitProperties.class,
  IdempotencyProperties.class,
  LookupProperties.class
})
class WalletConfiguration {

  @Bean
  LimitPolicy limitPolicy(LimitProperties limits) {
    return limits.toPolicy();
  }
}
