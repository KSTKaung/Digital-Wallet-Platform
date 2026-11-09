package com.portfolio.wallet.user.domain;

import com.portfolio.wallet.common.text.InvalidTextException;
import com.portfolio.wallet.common.text.TextRules;

/**
 * The shared rule for free-text KYC fields, applied by {@link TextRules} (documentations/06
 * MVP-ADR-10): NFC, surrounding whitespace removed, not blank, at most {@code max} characters
 * (counted as PostgreSQL counts {@code varchar}, in code points), and no control characters, lone
 * surrogates or format characters (J6). The rules live in {@code common.text} since PR 5a step 3,
 * so the transfer reviewer's reason (MVP-ADR-24 amendment Q10) applies the same implementation;
 * this class keeps the name rule (P1, R2) and reports every failure as {@link
 * InvalidKycDetailsException}. Messages name the field, never the value.
 */
final class KycText {

  private KycText() {}

  static String require(String field, String value, int max) {
    try {
      return TextRules.require(field, value, max);
    } catch (InvalidTextException e) {
      throw new InvalidKycDetailsException(e.field(), e.rule());
    }
  }

  /**
   * As {@link #require}, for a legal name (P1, R2): it must contain at least one letter, and ZWNJ
   * (U+200C) and ZWJ (U+200D) are allowed only between two letters or combining marks, never at the
   * start or the end and never next to another joiner. Counted in code points, so letters outside
   * the Basic Multilingual Plane count too.
   */
  static String requireName(String field, String value, int max) {
    String checked;
    try {
      checked = TextRules.requireAllowingJoiners(field, value, max);
    } catch (InvalidTextException e) {
      throw new InvalidKycDetailsException(e.field(), e.rule());
    }
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

  /**
   * As {@link #require}, for a reason a person reads: it must also contain at least one letter or
   * digit, so punctuation or symbols alone are refused (J6).
   */
  static String requireReason(String field, String value, int max) {
    try {
      return TextRules.requireReason(field, value, max);
    } catch (InvalidTextException e) {
      throw new InvalidKycDetailsException(e.field(), e.rule());
    }
  }
}
