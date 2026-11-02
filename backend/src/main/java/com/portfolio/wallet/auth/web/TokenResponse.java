package com.portfolio.wallet.auth.web;

/**
 * The refresh token is never here: it travels only in the cookie (documentations/06 MVP-ADR-14).
 */
record TokenResponse(String accessToken, long expiresIn) {

  @Override
  public String toString() {
    return "TokenResponse[expiresIn=" + expiresIn + "]";
  }
}
