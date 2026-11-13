package com.portfolio.wallet.common.text;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * The shared rule for free-text fields a person types (documentations/06 MVP-ADR-10, the KYC reason
 * text rules): NFC, surrounding whitespace removed, not blank, at most {@code max} characters
 * (counted as PostgreSQL counts {@code varchar}, in code points), and no control characters or lone
 * surrogates, which could not be stored or would end as a 500, and no format characters (zero-width
 * characters and bidirectional marks, J6), which are invisible and can make a text read differently
 * from what it contains. Messages name the field, never the value.
 *
 * <p>Moved here unchanged from {@code user.domain.KycText} (PR 5a step 3), so the KYC fields and
 * the transfer reviewer's reason (MVP-ADR-24 amendment Q10: "the KYC reason text rules") apply one
 * implementation.
 */
public final class TextRules {

  private static final Pattern FORBIDDEN = Pattern.compile("[\\p{Cc}\\p{Cs}\\p{Cf}]");

  /**
   * As {@link #FORBIDDEN}, but zero-width non-joiner (U+200C) and joiner (U+200D) are allowed:
   * names in Persian, several Indic scripts and others need them to be spelled correctly (owner,
   * 2026-11-04, P1). Only names; every other field and every other format character stays refused.
   */
  private static final Pattern FORBIDDEN_IN_NAMES =
      Pattern.compile("[\\p{Cc}\\p{Cs}\\p{Cf}&&[^\\u200C\\u200D]]");

  private static final Pattern LETTER_OR_DIGIT = Pattern.compile("[\\p{L}\\p{N}]");

  private TextRules() {}

  /**
   * @return the normalised text (NFC, stripped)
   * @throws InvalidTextException if a rule is broken
   */
  public static String require(String field, String value, int max) {
    return check(field, value, max, FORBIDDEN);
  }

  /**
   * As {@link #require}, but ZWNJ (U+200C) and ZWJ (U+200D) are allowed. Only for names; the caller
   * checks where a joiner may stand.
   */
  public static String requireAllowingJoiners(String field, String value, int max) {
    return check(field, value, max, FORBIDDEN_IN_NAMES);
  }

  /**
   * As {@link #require}, for a reason a person reads: it must also contain at least one letter or
   * digit, so punctuation or symbols alone are refused (J6).
   */
  public static String requireReason(String field, String value, int max) {
    String checked = require(field, value, max);
    if (!LETTER_OR_DIGIT.matcher(checked).find()) {
      throw new InvalidTextException(field, "must contain a letter or a digit");
    }
    return checked;
  }

  private static String check(String field, String value, int max, Pattern forbidden) {
    if (value == null) {
      throw new InvalidTextException(field, "must not be blank");
    }
    String normalised = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
    if (normalised.isEmpty()) {
      throw new InvalidTextException(field, "must not be blank");
    }
    if (forbidden.matcher(normalised).find()) {
      throw new InvalidTextException(field, "must not contain control or format characters");
    }
    if (normalised.codePointCount(0, normalised.length()) > max) {
      throw new InvalidTextException(field, "must have at most " + max + " characters");
    }
    return normalised;
  }
}
