package com.portfolio.wallet.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.portfolio.wallet.support.TestSecrets;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Format and failure messages of the required secrets (documentations/08 §4A.4 criterion 12). */
class RequiredSecretsTest {

  private static Map<String, String> validValues() {
    return new HashMap<>(TestSecrets.values());
  }

  private static RequiredSecrets load(Map<String, String> values) {
    return RequiredSecrets.from(values::get);
  }

  @Test
  void loadsValidSecretsAndDerivesAMatchingPublicKey() throws GeneralSecurityException {
    RequiredSecrets secrets = load(validValues());

    byte[] data = "payload".getBytes(StandardCharsets.UTF_8);
    Signature signer = Signature.getInstance("SHA256withRSA");
    signer.initSign(secrets.jwtPrivateKey());
    signer.update(data);
    byte[] signature = signer.sign();
    Signature verifier = Signature.getInstance("SHA256withRSA");
    verifier.initVerify(secrets.jwtPublicKey());
    verifier.update(data);

    assertThat(verifier.verify(signature)).isTrue();
    assertThat(secrets.jwtKid()).isEqualTo("test-key");
    assertThat(secrets.otpHmacKey()).hasSize(32);
    assertThat(secrets.refreshTokenPepper()).hasSize(32);
  }

  @Test
  void keyMaterialAccessorsReturnCopies() {
    RequiredSecrets secrets = load(validValues());

    secrets.otpHmacKey()[0] ^= 1;
    secrets.refreshTokenPepper()[0] ^= 1;

    assertThat(Base64.getEncoder().encodeToString(secrets.otpHmacKey()))
        .isEqualTo(TestSecrets.values().get(RequiredSecrets.OTP_HMAC_SECRET));
    assertThat(Base64.getEncoder().encodeToString(secrets.refreshTokenPepper()))
        .isEqualTo(TestSecrets.values().get(RequiredSecrets.REFRESH_TOKEN_PEPPER));
  }

  @Test
  void toStringRevealsNoValue() {
    String text = load(validValues()).toString();

    TestSecrets.values().values().forEach(value -> assertThat(text).doesNotContain(value));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        RequiredSecrets.JWT_PRIVATE_KEY,
        RequiredSecrets.JWT_KID,
        RequiredSecrets.OTP_HMAC_SECRET,
        RequiredSecrets.REFRESH_TOKEN_PEPPER
      })
  void missingOrBlankSecretIsNamed(String name) {
    Map<String, String> absent = validValues();
    absent.remove(name);
    Map<String, String> blank = validValues();
    blank.put(name, "  ");

    assertThatThrownBy(() -> load(absent))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(name);
    assertThatThrownBy(() -> load(blank))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(name);
  }

  @Test
  void everyMissingSecretIsNamedAtOnce() {
    assertThatThrownBy(() -> load(Map.of()))
        .hasMessageContainingAll(
            RequiredSecrets.JWT_PRIVATE_KEY,
            RequiredSecrets.JWT_KID,
            RequiredSecrets.OTP_HMAC_SECRET,
            RequiredSecrets.REFRESH_TOKEN_PEPPER);
  }

  /** A valid RSA key in the encoding the secrets script used to print by mistake. */
  private static String pkcs1Key() throws IOException {
    byte[] pkcs8 = Base64.getDecoder().decode(TestSecrets.rsaPrivateKey(2048));
    byte[] pkcs1 =
        PrivateKeyInfo.getInstance(pkcs8).parsePrivateKey().toASN1Primitive().getEncoded();
    return Base64.getEncoder().encodeToString(pkcs1);
  }

  static Stream<Arguments> invalidValues() throws GeneralSecurityException, IOException {
    String placeholder = "generate-with-scripts/generate-dev-secrets.sh";
    KeyPairGenerator ec = KeyPairGenerator.getInstance("EC");
    String ecKey =
        Base64.getEncoder().encodeToString(ec.generateKeyPair().getPrivate().getEncoded());
    return Stream.of(
        Arguments.of(RequiredSecrets.JWT_PRIVATE_KEY, placeholder),
        Arguments.of(RequiredSecrets.JWT_PRIVATE_KEY, pkcs1Key()),
        Arguments.of(RequiredSecrets.JWT_PRIVATE_KEY, "c2hvcnQ="),
        Arguments.of(RequiredSecrets.JWT_PRIVATE_KEY, ecKey),
        Arguments.of(RequiredSecrets.JWT_PRIVATE_KEY, TestSecrets.rsaPrivateKey(1024)),
        Arguments.of(RequiredSecrets.JWT_KID, placeholder),
        Arguments.of(RequiredSecrets.JWT_KID, "k".repeat(65)),
        Arguments.of(RequiredSecrets.OTP_HMAC_SECRET, placeholder),
        Arguments.of(RequiredSecrets.OTP_HMAC_SECRET, TestSecrets.randomBase64(31)),
        Arguments.of(RequiredSecrets.REFRESH_TOKEN_PEPPER, placeholder),
        Arguments.of(RequiredSecrets.REFRESH_TOKEN_PEPPER, TestSecrets.randomBase64(31)));
  }

  /** The .env.example placeholder must not start the application with a meaningless secret. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidValues")
  void invalidValueIsRejectedNamingTheVariableButNotTheValue(String name, String value) {
    Map<String, String> values = validValues();
    values.put(name, value);

    Throwable thrown = catchThrowable(() -> load(values));

    assertThat(thrown).isInstanceOf(IllegalStateException.class).hasNoCause();
    assertThat(thrown.getMessage()).contains(name).doesNotContain(value);
  }
}
