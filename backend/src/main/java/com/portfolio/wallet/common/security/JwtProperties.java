package com.portfolio.wallet.common.security;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Token settings shared by signing and validation. The claim names are part of the token's format.
 *
 * @param issuer the {@code iss} claim
 */
@Validated
@ConfigurationProperties(prefix = "app.jwt")
public record JwtProperties(@NotBlank String issuer) {

  public static final String ROLES_CLAIM = "roles";
  public static final String SESSION_CLAIM = "sid";
}
