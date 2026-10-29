package com.portfolio.wallet.common.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Allowed browser origins for the SPA ({@code app.cors.allowed-origins}). */
@ConfigurationProperties(prefix = "app.cors")
public record CorsProperties(List<String> allowedOrigins) {

  public CorsProperties {
    allowedOrigins = allowedOrigins == null ? List.of() : List.copyOf(allowedOrigins);
  }
}
