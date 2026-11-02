package com.portfolio.wallet.auth.web;

/** {@link #toString()} leaves the token out. */
record ResetTokenResponse(String resetToken) {

  @Override
  public String toString() {
    return "ResetTokenResponse[]";
  }
}
