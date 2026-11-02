package com.portfolio.wallet.auth.application;

import java.time.Duration;

/**
 * What a login or refresh hands to the web layer. {@link #toString()} leaves the tokens out.
 *
 * @param accessToken for the response body
 * @param expiresInSeconds for the response body
 * @param refreshToken for the cookie only
 * @param refreshMaxAge the cookie's Max-Age
 */
public record IssuedTokens(
    String accessToken, long expiresInSeconds, String refreshToken, Duration refreshMaxAge) {

  @Override
  public String toString() {
    return "IssuedTokens[expiresInSeconds=" + expiresInSeconds + "]";
  }
}
