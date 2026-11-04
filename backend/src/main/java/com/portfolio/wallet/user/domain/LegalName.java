package com.portfolio.wallet.user.domain;

/**
 * The legal name captured in a KYC application and copied to the profile on approval
 * (documentations/06 MVP-ADR-10 amendment, doc 08 §4B.2 Q5). It is Confidential data (doc 03 §5.1),
 * so {@link #toString()} leaves it out.
 */
public record LegalName(String firstName, String lastName) {

  /** The size of the name columns in V8. */
  public static final int MAX_LENGTH = 100;

  public LegalName {
    firstName = requirePart("firstName", firstName);
    lastName = requirePart("lastName", lastName);
  }

  /**
   * The rule for one part of a legal name (P1, R2), also used by the request layer so both apply
   * the same rule (T3): one linear pass over the code points, with no regular expression that could
   * recurse on long input.
   *
   * @return the value, normalised
   * @throws InvalidKycDetailsException naming {@code field} and the rule, never the value
   */
  public static String requirePart(String field, String value) {
    return KycText.requireName(field, value, MAX_LENGTH);
  }

  /**
   * What another customer may see, for the recipient lookup (MVP-ADR-20): the first name and the
   * initial of the last name, as in {@code Somchai J.}. The initial is the last name's first letter
   * (a whole code point), so a leading apostrophe, hyphen or digit is skipped and the initial is
   * always a letter: every legal name contains one (R2).
   */
  public String masked() {
    int initial = lastName.codePoints().filter(Character::isLetter).findFirst().orElseThrow();
    return firstName + " " + Character.toString(initial) + ".";
  }

  @Override
  public String toString() {
    return "LegalName[]";
  }
}
