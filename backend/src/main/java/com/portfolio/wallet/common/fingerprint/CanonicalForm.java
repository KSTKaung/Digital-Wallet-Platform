package com.portfolio.wallet.common.fingerprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The hashing core of a request fingerprint (documentations/02 §1.4; doc 07 §7): a canonical form
 * of the <em>validated</em> request, built line by line, and its SHA-256 in lower-case hex. Each
 * module defines its own form, with its own version line, in its domain ({@code
 * wallet-idempotency-v1}, {@code transfer-idempotency-v1}); this class only fixes how lines are
 * written, so every form is unambiguous:
 *
 * <pre>
 * &lt;version line&gt;
 * &lt;name&gt;=&lt;value&gt;                 (a fixed field: an operation, a UUID, an amount, a code)
 * &lt;name&gt;=&lt;length&gt;:&lt;value&gt;        (a text field; length in UTF-16 units)
 * &lt;name&gt;=null                    (a text field without a value)
 * </pre>
 *
 * Every line ends in {@code \n} and the text is UTF-8. Text values are written exactly as given (no
 * trimming or Unicode normalisation); the length prefix keeps a value that contains {@code \n} or
 * {@code =} unambiguous, and {@code null} differs from the empty string. Changing a module's form
 * changes every hash of that module: give it a new version line.
 */
public final class CanonicalForm {

  private static final Pattern VERSION_LINE = Pattern.compile("[a-z][a-z0-9-]{0,63}-v[0-9]{1,3}");
  private static final Pattern FIELD_NAME = Pattern.compile("[a-z][A-Za-z0-9]{0,39}");

  private CanonicalForm() {}

  /**
   * Starts a form with its version line.
   *
   * @param versionLine a lower-case name ending in {@code -v<n>}, such as {@code
   *     wallet-idempotency-v1}
   */
  public static Builder version(String versionLine) {
    Objects.requireNonNull(versionLine, "versionLine");
    if (!VERSION_LINE.matcher(versionLine).matches()) {
      throw new IllegalArgumentException("invalid canonical form version line");
    }
    return new Builder(versionLine);
  }

  /** SHA-256 of {@code text} in UTF-8, as 64 lower-case hex characters. */
  public static String sha256Hex(String text) {
    Objects.requireNonNull(text, "text");
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      // Every Java platform must provide SHA-256.
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }

  /** Appends lines in the order they are added; each field name is used once. */
  public static final class Builder {

    private final StringBuilder form = new StringBuilder(128);
    private final Set<String> names = new HashSet<>();

    private Builder(String versionLine) {
      line(versionLine);
    }

    /**
     * Adds a fixed field: a value whose format the caller controls and which never holds a line
     * break, such as an operation, a UUID, an amount as a plain string or a currency code.
     *
     * @param name a lower camel-case identifier, used once
     * @throws IllegalArgumentException if the value is null or holds a line break
     */
    public Builder fixed(String name, String value) {
      requireNewName(name);
      Objects.requireNonNull(value, "value");
      if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
        throw new IllegalArgumentException("a fixed canonical field holds no line break");
      }
      line(name + "=" + value);
      return this;
    }

    /**
     * Adds a text field: the validated value with its length, or {@code null}.
     *
     * @param name a lower camel-case identifier, used once
     * @param value the validated value, or null when the request has none
     */
    public Builder text(String name, String value) {
      requireNewName(name);
      line(name + "=" + (value == null ? "null" : value.length() + ":" + value));
      return this;
    }

    /** The canonical form; it holds request values, so it is never logged. */
    public String build() {
      return form.toString();
    }

    private void requireNewName(String name) {
      Objects.requireNonNull(name, "name");
      if (!FIELD_NAME.matcher(name).matches()) {
        throw new IllegalArgumentException("invalid canonical field name");
      }
      if (!names.add(name)) {
        throw new IllegalArgumentException("canonical field added twice");
      }
    }

    private void line(String text) {
      form.append(text).append('\n');
    }
  }
}
