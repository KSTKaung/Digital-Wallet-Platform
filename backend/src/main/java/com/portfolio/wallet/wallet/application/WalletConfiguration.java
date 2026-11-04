package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.wallet.domain.LimitPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds {@link WalletProperties} and {@link LimitProperties}; builds the {@link LimitPolicy}. */
@Configuration
@EnableConfigurationProperties({WalletProperties.class, LimitProperties.class})
class WalletConfiguration {

  @Bean
  LimitPolicy limitPolicy(LimitProperties limits) {
    return limits.toPolicy();
  }
}
