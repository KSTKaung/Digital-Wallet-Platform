package com.portfolio.wallet.support;

import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;

/**
 * Values for the required secrets, generated once per test JVM. No key material is ever committed:
 * Gitleaks scans the full history, and these values are useless outside the run that made them.
 */
public final class TestSecrets {

  private static final Map<String, String> VALUES =
      Map.of(
          "JWT_PRIVATE_KEY",
          rsaPrivateKey(2048),
          "JWT_KID",
          "test-key",
          "OTP_HMAC_SECRET",
          randomBase64(32),
          "REFRESH_TOKEN_PEPPER",
          randomBase64(32));

  private TestSecrets() {}

  /** Variable name to value, in the format {@code RequiredSecrets} reads. */
  public static Map<String, String> values() {
    return VALUES;
  }

  /** An RSA private key as single-line base64 of its PKCS#8 DER encoding. */
  public static String rsaPrivateKey(int bits) {
    try {
      KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
      generator.initialize(bits);
      return Base64.getEncoder()
          .encodeToString(generator.generateKeyPair().getPrivate().getEncoded());
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException(e);
    }
  }

  public static String randomBase64(int bytes) {
    byte[] value = new byte[bytes];
    new SecureRandom().nextBytes(value);
    return Base64.getEncoder().encodeToString(value);
  }
}
