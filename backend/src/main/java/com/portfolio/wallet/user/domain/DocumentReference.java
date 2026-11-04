package com.portfolio.wallet.user.domain;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The identity document's number as the customer typed it (documentations/08 §4B.2 Q6). Nothing
 * checks it is real: this is a review workflow, not identity verification. It is Restricted KYC
 * content (doc 03 §5.1): always returned masked, to staff too, and never logged, so {@link
 * #toString()} leaves it out.
 *
 * <p>Only non-blank, at most {@value #MAX_LENGTH} characters and no control characters are
 * documented (format characters are refused too, J6); no minimum length or character set is (owner
 * question open, 2026-11-03).
 */
public record DocumentReference(String value) {

  /** The size of {@code usr.kyc_applications.document_reference}. */
  public static final int MAX_LENGTH = 50;

  private static final Pattern NON_ALPHANUMERIC = Pattern.compile("[^\\p{L}\\p{N}]");

  public DocumentReference {
    value = KycText.require("documentReference", value, MAX_LENGTH);
  }

  /**
   * The mask and the last 4 characters, only when at least 4 others stay hidden (8 characters or
   * more); a shorter reference is masked whole (owner, 2026-11-03, K5).
   */
  public String masked() {
    return Masking.documentReference(value);
  }

  /**
   * Whether {@code text} repeats the reference, used to refuse a rejection reason that would show
   * it to the customer (Q13). Both sides are compared in a {@linkplain #comparable comparable
   * form}, so {@code AB-123 4567}, {@code ab.1234.567} and the same characters in full-width form
   * all match {@code AB1234567}. A reference with no letters or digits at all is compared
   * case-insensitively as it is.
   */
  public boolean appearsIn(String text) {
    if (text == null) {
      return false;
    }
    String needle = comparable(value);
    if (needle.isEmpty()) {
      return text.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT));
    }
    return comparable(text).contains(needle);
  }

  /**
   * NFKC (full-width and other compatibility forms become their plain equivalents), lower case in
   * {@link Locale#ROOT}, then everything that is not a letter or a digit removed: whitespace,
   * hyphens, dots, slashes and every other separator or symbol, and combining marks.
   */
  static String comparable(String text) {
    return NON_ALPHANUMERIC
        .matcher(Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT))
        .replaceAll("");
  }

  @Override
  public String toString() {
    return "DocumentReference[]";
  }
}
