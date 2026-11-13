package com.portfolio.wallet.risk.application;

import com.portfolio.wallet.risk.domain.RiskPolicy;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Binds {@link RiskProperties}; builds the {@link RiskPolicy}. */
@Configuration
@EnableConfigurationProperties(RiskProperties.class)
class RiskConfiguration {

  @Bean
  RiskPolicy riskPolicy(RiskProperties properties) {
    return properties.toPolicy();
  }
}
