package com.portfolio.wallet.common.security;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The secrets the application cannot run without (documentations/06 MVP-ADR-23): they come only
 * from the environment, have no default in any profile, and are checked when the context starts.
 *
 * <ul>
 *   <li>{@value #JWT_PRIVATE_KEY}: RSA private key of at least 2048 bits (RS256, RFC 7518 §3.3) as
 *       single-line base64 of its PKCS#8 DER encoding, without PEM markers, so it fits one {@code
 *       .env} line and one Compose variable. The public key is derived from it.
 *   <li>{@value #JWT_KID}: key id for the JWT header.
 *   <li>{@value #OTP_HMAC_SECRET} and {@value #REFRESH_TOKEN_PEPPER}: base64 of at least 32 bytes,
 *       the output size of HMAC-SHA256 (RFC 2104 §3).
 * </ul>
 *
 * <p>Failure messages name the variable, never its value, and carry no cause, because a decoder's
 * message can quote part of its input. Deliberately not {@code @ConfigurationProperties}: Boot's
 * binder leaves an unresolved {@code ${VAR}} in place as a literal, and its failure report prints
 * the bound value. This class has no accessor for the raw strings and no value-bearing {@code
 * toString}.
 */
public final class RequiredSecrets {

  public static final String JWT_PRIVATE_KEY = "JWT_PRIVATE_KEY";
  public static final String JWT_KID = "JWT_KID";
  public static final String OTP_HMAC_SECRET = "OTP_HMAC_SECRET";
  public static final String REFRESH_TOKEN_PEPPER = "REFRESH_TOKEN_PEPPER";

  private static final int MIN_RSA_KEY_BITS = 2048;
  private static final int MIN_SYMMETRIC_KEY_BYTES = 32;
  private static final Pattern VALID_KID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

  private final RSAPrivateCrtKey jwtPrivateKey;
  private final RSAPublicKey jwtPublicKey;
  private final String jwtKid;
  private final byte[] otpHmacKey;
  private final byte[] refreshTokenPepper;

  private RequiredSecrets(
      RSAPrivateCrtKey jwtPrivateKey,
      RSAPublicKey jwtPublicKey,
      String jwtKid,
      byte[] otpHmacKey,
      byte[] refreshTokenPepper) {
    this.jwtPrivateKey = jwtPrivateKey;
    this.jwtPublicKey = jwtPublicKey;
    this.jwtKid = jwtKid;
    this.otpHmacKey = otpHmacKey;
    this.refreshTokenPepper = refreshTokenPepper;
  }

  /**
   * Reads and validates all four secrets.
   *
   * @param lookup returns a variable's value, or null when it is not set
   * @throws IllegalStateException naming every missing variable, or the first invalid one
   */
  public static RequiredSecrets from(UnaryOperator<String> lookup) {
    List<String> names = List.of(JWT_PRIVATE_KEY, JWT_KID, OTP_HMAC_SECRET, REFRESH_TOKEN_PEPPER);
    List<String> missing =
        names.stream()
            .filter(
                name -> {
                  String value = lookup.apply(name);
                  return value == null || value.isBlank();
                })
            .toList();
    if (!missing.isEmpty()) {
      throw new IllegalStateException(
          "Required secret environment variable not set: " + String.join(", ", missing));
    }

    RSAPrivateCrtKey privateKey = parseRsaPrivateKey(lookup.apply(JWT_PRIVATE_KEY));
    String kid = lookup.apply(JWT_KID);
    if (!VALID_KID.matcher(kid).matches()) {
      throw invalid(JWT_KID, "1-64 characters: letters, digits, '.', '_' or '-'");
    }
    return new RequiredSecrets(
        privateKey,
        derivePublicKey(privateKey),
        kid,
        decodeSymmetricKey(OTP_HMAC_SECRET, lookup.apply(OTP_HMAC_SECRET)),
        decodeSymmetricKey(REFRESH_TOKEN_PEPPER, lookup.apply(REFRESH_TOKEN_PEPPER)));
  }

  public RSAPrivateCrtKey jwtPrivateKey() {
    return jwtPrivateKey;
  }

  public RSAPublicKey jwtPublicKey() {
    return jwtPublicKey;
  }

  public String jwtKid() {
    return jwtKid;
  }

  public byte[] otpHmacKey() {
    return otpHmacKey.clone();
  }

  public byte[] refreshTokenPepper() {
    return refreshTokenPepper.clone();
  }

  private static RSAPrivateCrtKey parseRsaPrivateKey(String base64) {
    String expected = "base64 of a PKCS#8 DER RSA private key of at least 2048 bits";
    PrivateKey key;
    try {
      byte[] der = Base64.getDecoder().decode(base64);
      key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
    } catch (IllegalArgumentException | GeneralSecurityException e) {
      throw invalid(JWT_PRIVATE_KEY, expected);
    }
    if (key instanceof RSAPrivateCrtKey crtKey
        && crtKey.getModulus().bitLength() >= MIN_RSA_KEY_BITS) {
      return crtKey;
    }
    throw invalid(JWT_PRIVATE_KEY, expected);
  }

  private static RSAPublicKey derivePublicKey(RSAPrivateCrtKey privateKey) {
    BigInteger modulus = privateKey.getModulus();
    try {
      return (RSAPublicKey)
          KeyFactory.getInstance("RSA")
              .generatePublic(new RSAPublicKeySpec(modulus, privateKey.getPublicExponent()));
    } catch (GeneralSecurityException e) {
      throw invalid(JWT_PRIVATE_KEY, "a usable RSA key");
    }
  }

  private static byte[] decodeSymmetricKey(String name, String base64) {
    String expected = "base64 of at least " + MIN_SYMMETRIC_KEY_BYTES + " bytes";
    byte[] key;
    try {
      key = Base64.getDecoder().decode(base64);
    } catch (IllegalArgumentException e) {
      throw invalid(name, expected);
    }
    if (key.length < MIN_SYMMETRIC_KEY_BYTES) {
      throw invalid(name, expected);
    }
    return key;
  }

  private static IllegalStateException invalid(String name, String expected) {
    return new IllegalStateException(
        "Required secret environment variable " + name + " is invalid: expected " + expected);
  }
}
