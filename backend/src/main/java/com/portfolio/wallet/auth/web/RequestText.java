package com.portfolio.wallet.auth.web;

/**
 * What the request fields may contain. A NUL cannot be stored or even bound in PostgreSQL text, and
 * a lone surrogate cannot be encoded as UTF-8, so both would otherwise end as a 500. The patterns
 * let a surrogate pair through, because it is one valid character.
 */
final class RequestText {

  /** For addresses: no control characters and no lone surrogates. */
  static final String NO_CONTROL_CHARACTERS = "[^\\p{Cc}\\p{Cs}]*";

  static final String NO_CONTROL_CHARACTERS_MESSAGE = "must not contain control characters";

  /** For passwords, where anything encodable is allowed. */
  static final String ENCODABLE = "[^\\p{Cs}]*";

  static final String ENCODABLE_MESSAGE = "must be valid text";

  /** The size of {@code auth.users.email}. */
  static final int MAX_EMAIL_LENGTH = 254;

  private RequestText() {}
}
