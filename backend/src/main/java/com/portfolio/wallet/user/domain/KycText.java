package com.portfolio.wallet.user.domain;

import java.text.Normalizer;
import java.util.regex.Pattern;

/**
 * The shared rule for free-text KYC fields: NFC, surrounding whitespace removed, not blank, at most
 * {@code max} characters (counted as PostgreSQL counts {@code varchar}, in code points), and no
 * control characters or lone surrogates, which could not be stored or would end as a 500, and no
 * format characters (zero-width characters and bidirectional marks, J6), which are invisible and
 * can make a text read differently from what it contains. Messages name the field, never the value.
 */
final class KycText {

  private static final Pattern FORBIDDEN = Pattern.compile("[\\p{Cc}\\p{Cs}\\p{Cf}]");

  /**
   * As {@link #FORBIDDEN}, but zero-width non-joiner (U+200C) and joiner (U+200D) are allowed:
   * names in Persian, several Indic scripts and others need them to be spelled correctly (owner,
   * 2026-11-04, P1). Only names; every other field and every other format character stays refused.
   */
  private static final Pattern FORBIDDEN_IN_NAMES =
      Pattern.compile("[\\p{Cc}\\p{Cs}\\p{Cf}&&[^\\u200C\\u200D]]");

  private static final Pattern LETTER_OR_DIGIT = Pattern.compile("[\\p{L}\\p{N}]");

  private KycText() {}

  static String require(String field, String value, int max) {
    return check(field, value, max, FORBIDDEN);
  }

  /**
   * As {@link #require}, for a legal name (P1, R2): it must contain at least one letter, and ZWNJ
   * (U+200C) and ZWJ (U+200D) are allowed only between two letters or combining marks, never at the
   * start or the end and never next to another joiner. Counted in code points, so letters outside
   * the Basic Multilingual Plane count too.
   */
  static String requireName(String field, String value, int max) {
    String checked = check(field, value, max, FORBIDDEN_IN_NAMES);
    int[] codePoints = checked.codePoints().toArray();
    boolean letter = false;
    for (int i = 0; i < codePoints.length; i++) {
      int cp = codePoints[i];
      letter |= Character.isLetter(cp);
      if (isJoiner(cp)
          && (i == 0
              || i == codePoints.length - 1
              || !isLetterOrMark(codePoints[i - 1])
              || !isLetterOrMark(codePoints[i + 1]))) {
        throw new InvalidKycDetailsException(
            field, "may contain a zero-width joiner or non-joiner only between letters");
      }
    }
    if (!letter) {
      throw new InvalidKycDetailsException(field, "must contain a letter");
    }
    return checked;
  }

  private static boolean isJoiner(int codePoint) {
    return codePoint == 0x200C || codePoint == 0x200D;
  }

  /** A letter, or a combining mark (non-spacing, spacing or enclosing). */
  static boolean isLetterOrMark(int codePoint) {
    if (Character.isLetter(codePoint)) {
      return true;
    }
    int type = Character.getType(codePoint);
    return type == Character.NON_SPACING_MARK
        || type == Character.COMBINING_SPACING_MARK
        || type == Character.ENCLOSING_MARK;
  }

  private static String check(String field, String value, int max, Pattern forbidden) {
    if (value == null) {
      throw new InvalidKycDetailsException(field, "must not be blank");
    }
    String normalised = Normalizer.normalize(value, Normalizer.Form.NFC).strip();
    if (normalised.isEmpty()) {
      throw new InvalidKycDetailsException(field, "must not be blank");
    }
    if (forbidden.matcher(normalised).find()) {
      throw new InvalidKycDetailsException(field, "must not contain control or format characters");
    }
    if (normalised.codePointCount(0, normalised.length()) > max) {
      throw new InvalidKycDetailsException(field, "must have at most " + max + " characters");
    }
    return normalised;
  }

  /**
   * As {@link #require}, for a reason a person reads: it must also contain at least one letter or
   * digit, so punctuation or symbols alone are refused (J6).
   */
  static String requireReason(String field, String value, int max) {
    String checked = require(field, value, max);
    if (!LETTER_OR_DIGIT.matcher(checked).find()) {
      throw new InvalidKycDetailsException(field, "must contain a letter or a digit");
    }
    return checked;
  }
}
