package com.portfolio.wallet.auth.domain;

import java.text.Normalizer;
import java.util.Locale;

/** The canonical form of an email address, used for every read, write and keyed hash. */
public final class EmailAddress {

  private EmailAddress() {}

  /** NFC and lower case; normalised again because lower-casing can denormalise a string. */
  public static String canonical(String email) {
    String lower = nfc(email).toLowerCase(Locale.ROOT);
    return nfc(lower);
  }

  private static String nfc(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFC);
  }
}
