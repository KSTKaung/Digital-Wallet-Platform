package com.portfolio.wallet.user.domain;

/**
 * Display masks (documentations/06 MVP-ADR-10 amendment, doc 08 §4B.2 Q15). Masking is not
 * encryption: the stored values stay in plain text (MVP-ADR-12).
 *
 * <p>The mask is always the fixed {@value #MASK}, so it does not reveal the length of what it
 * hides.
 */
public final class Masking {

  public static final String MASK = "***";

  private static final int VISIBLE = 4;

  private Masking() {}

  /**
   * {@code alice@example.com} becomes {@code a***@example.com}: the first character, the mask, then
   * the domain. Anything without a usable {@code @} is masked whole.
   */
  public static String email(String email) {
    if (email == null) {
      return null;
    }
    int at = email.lastIndexOf('@');
    if (at <= 0) {
      return MASK;
    }
    int firstEnd = email.offsetByCodePoints(0, 1);
    if (firstEnd > at) {
      return MASK;
    }
    return email.substring(0, firstEnd) + MASK + email.substring(at);
  }

  /** The last 4 digits only: {@code +66812345678} becomes {@code ***5678}. */
  public static String phone(String phoneNumber) {
    return lastFour(phoneNumber);
  }

  /**
   * A KYC document reference: the mask and the last 4 characters only when at least as many
   * characters stay hidden, so at 8 characters or more; anything shorter is masked whole (owner,
   * 2026-11-03, K5). Showing 4 of 5 characters would reveal nearly the whole reference.
   */
  public static String documentReference(String value) {
    if (value == null) {
      return null;
    }
    if (value.codePointCount(0, value.length()) < 2 * VISIBLE) {
      return MASK;
    }
    return lastFour(value);
  }

  /**
   * The mask and the last 4 characters. A value of 4 characters or fewer is masked whole, so a
   * short value is never shown in full.
   */
  public static String lastFour(String value) {
    if (value == null) {
      return null;
    }
    int length = value.codePointCount(0, value.length());
    if (length <= VISIBLE) {
      return MASK;
    }
    return MASK + value.substring(value.offsetByCodePoints(0, length - VISIBLE));
  }
}
