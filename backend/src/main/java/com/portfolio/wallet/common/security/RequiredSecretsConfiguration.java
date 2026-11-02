package com.portfolio.wallet.common.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Loads {@link RequiredSecrets} eagerly, so a missing secret stops the application at startup. Test
 * slices do not scan this class, so web-slice tests need no secrets.
 */
@Configuration
public class RequiredSecretsConfiguration {

  @Bean
  RequiredSecrets requiredSecrets(ConfigurableEnvironment environment) {
    return RequiredSecrets.from(RawEnvironment.lookup(environment));
  }
}
