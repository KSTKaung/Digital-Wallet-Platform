package com.portfolio.wallet.auth.application;

import com.portfolio.wallet.common.security.RequiredSecrets;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * HMAC-SHA256 hex digests, the form of every hash column in V7 except passwords. The prefixes keep
 * the uses of one key apart, and an OTP's digest is bound to its identifier and purpose.
 */
@Component
public class KeyedHasher {

  private static final String ALGORITHM = "HmacSHA256";

  private final byte[] otpKey;
  private final byte[] refreshPepper;

  public KeyedHasher(RequiredSecrets secrets) {
    this.otpKey = secrets.otpHmacKey();
    this.refreshPepper = secrets.refreshTokenPepper();
  }

  public String identifier(String canonicalIdentifier) {
    return hmac(otpKey, "identifier:" + canonicalIdentifier);
  }

  public String otp(String identifierHash, String purpose, String code) {
    return hmac(otpKey, "otp:" + identifierHash + ":" + purpose + ":" + code);
  }

  public String refreshToken(String token) {
    return hmac(refreshPepper, token);
  }

  public String resetToken(String token) {
    return hmac(otpKey, "reset-token:" + token);
  }

  /** Constant-time comparison of two digests. */
  public static boolean sameDigest(String left, String right) {
    return MessageDigest.isEqual(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private static String hmac(byte[] key, String value) {
    try {
      Mac mac = Mac.getInstance(ALGORITHM);
      mac.init(new SecretKeySpec(key, ALGORITHM));
      return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HMAC-SHA256 is not available", e);
    }
  }
}
