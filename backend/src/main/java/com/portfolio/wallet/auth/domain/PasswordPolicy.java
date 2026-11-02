package com.portfolio.wallet.auth.domain;

import java.text.Normalizer;
import java.util.List;
import java.util.function.Predicate;

/**
 * Password rules (documentations/06 MVP-ADR-23): length in Unicode code points after NFC
 * normalisation, no reuse of the last passwords.
 */
public record PasswordPolicy(int minLength, int maxLength, int historyDepth) {

  public static String normalize(String password) {
    return Normalizer.normalize(password, Normalizer.Form.NFC);
  }

  public boolean isValidLength(String password) {
    int length = codePoints(password);
    return length >= minLength && length <= maxLength;
  }

  public boolean isOverMaximum(String password) {
    return codePoints(password) > maxLength;
  }

  /**
   * @param previousHashes newest first
   * @param matchesCandidate whether a stored hash is a hash of the candidate password
   */
  public boolean reusesRecent(List<String> previousHashes, Predicate<String> matchesCandidate) {
    return previousHashes.stream().limit(historyDepth).anyMatch(matchesCandidate);
  }

  private static int codePoints(String password) {
    String normalized = normalize(password);
    return normalized.codePointCount(0, normalized.length());
  }
}
