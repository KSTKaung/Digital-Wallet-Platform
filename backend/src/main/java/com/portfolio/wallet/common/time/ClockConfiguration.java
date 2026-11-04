package com.portfolio.wallet.common.time;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The application's time source, which tests replace with a fixed clock, and the shared business
 * calendar ({@link BusinessTimeProperties}).
 */
@Configuration
@EnableConfigurationProperties(BusinessTimeProperties.class)
public class ClockConfiguration {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
