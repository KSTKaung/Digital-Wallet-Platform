package com.portfolio.wallet.transfer.web;

/**
 * Request text rules of the transfer endpoints, as in the other modules' own copies. The client
 * address has one path, {@code ClientAddresses.parse(request.getRemoteAddr())} (common.security),
 * for both the transfer and the cancel.
 */
final class RequestText {

  /** No control, surrogate or format characters (J6). */
  static final String NO_CONTROL_CHARACTERS = "[^\\p{Cc}\\p{Cs}\\p{Cf}]*";

  static final String NO_CONTROL_CHARACTERS_MESSAGE =
      "must not contain control or format characters";

  /**
   * At most 140 characters (documentations/08 Q4; V4 {@code varchar(140)}), counted as PostgreSQL
   * counts them, in code points: in Java regular expressions {@code .} matches one code point, so a
   * character outside the BMP counts once, as in {@code TransferDetails}.
   */
  static final String AT_MOST_140_CHARACTERS = "(?s).{0,140}";

  static final String AT_MOST_140_CHARACTERS_MESSAGE = "must be at most 140 characters";

  /** Exactly 11 ASCII digits (documentations/02 §2.4 step 1; MVP-ADR-20, X2). */
  static final String WALLET_NUMBER = "[0-9]{11}";

  static final String WALLET_NUMBER_MESSAGE = "must be 11 digits";

  private RequestText() {}
}
