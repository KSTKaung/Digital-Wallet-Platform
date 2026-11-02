package com.portfolio.wallet.auth.domain;

/** Phone format (documentations/08 §4A.2 Q11): the E.164 pattern only. */
public final class PhoneNumber {

  /** Also the {@code ck_users_phone_number} constraint in V7. */
  public static final String E164_PATTERN = "\\+[0-9]{8,15}";

  private PhoneNumber() {}
}
