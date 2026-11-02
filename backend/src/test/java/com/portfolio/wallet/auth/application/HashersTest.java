package com.portfolio.wallet.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class HashersTest {

  private final PasswordHasher passwords = new PasswordHasher(AuthTestFixtures.PROPERTIES);
  private final KeyedHasher keyed = new KeyedHasher(AuthTestFixtures.SECRETS);

  @Test
  void passwordHashesAreArgon2idAndSalted() {
    String first = passwords.hash("a-long-password");
    assertThat(first).startsWith("$argon2id$");
    assertThat(passwords.hash("a-long-password")).isNotEqualTo(first);
    assertThat(passwords.matches("a-long-password", first)).isTrue();
    assertThat(passwords.matches("another-password", first)).isFalse();
  }

  @Test
  void passwordsAreNormalisedBeforeHashing() {
    assertThat(passwords.matches("café-password", passwords.hash("café-password"))).isTrue();
  }

  /** The encoder itself returns at once for an empty input, which timing would reveal. */
  @Test
  void anEmptyPasswordNeverMatchesButStillRunsTheHash() {
    String stored = passwords.hash("a-long-password");
    try (org.mockito.MockedConstruction<
            org.springframework.security.crypto.argon2.Argon2PasswordEncoder>
        encoders =
            org.mockito.Mockito.mockConstruction(
                org.springframework.security.crypto.argon2.Argon2PasswordEncoder.class,
                (encoder, context) ->
                    org.mockito.Mockito.when(
                            encoder.matches(
                                org.mockito.ArgumentMatchers.any(),
                                org.mockito.ArgumentMatchers.any()))
                        .thenReturn(true))) {
      PasswordHasher hasher = new PasswordHasher(AuthTestFixtures.PROPERTIES);
      var encoder = encoders.constructed().getFirst();

      assertThat(hasher.matches("", stored)).isFalse();
      hasher.dummyVerify("");

      org.mockito.Mockito.verify(encoder, org.mockito.Mockito.times(2))
          .matches(
              org.mockito.ArgumentMatchers.argThat(input -> !input.isEmpty()),
              org.mockito.ArgumentMatchers.any());
    }
    assertThat(passwords.matches("", stored)).isFalse();
  }

  @Test
  void aNulInAPasswordIsJustACharacter() {
    String stored = passwords.hash("a-long\u0000password");
    assertThat(passwords.matches("a-long\u0000password", stored)).isTrue();
    assertThat(passwords.matches("a-long", stored)).isFalse();
  }

  @Test
  void hashingManyAtOnceWorksWithinTheBound() throws Exception {
    try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
      List<Callable<String>> tasks = new ArrayList<>();
      for (int i = 0; i < 8; i++) {
        tasks.add(() -> passwords.hash("a-long-password"));
      }
      for (var future : pool.invokeAll(tasks)) {
        assertThat(future.get()).startsWith("$argon2id$");
      }
    }
  }

  @Test
  void aDummyVerificationNeverThrowsAndAnInterruptIsReported() {
    passwords.dummyVerify("anything");

    Thread.currentThread().interrupt();
    assertThatThrownBy(() -> passwords.dummyVerify("anything"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(Thread.interrupted()).isTrue();
  }

  @Test
  void keyedDigestsAreHexAndKeptApartByUse() {
    assertThat(keyed.identifier("a@b.test")).matches("[0-9a-f]{64}");
    assertThat(keyed.identifier("a@b.test")).isEqualTo(keyed.identifier("a@b.test"));
    assertThat(keyed.refreshToken("a@b.test")).isNotEqualTo(keyed.identifier("a@b.test"));
    assertThat(keyed.resetToken("a@b.test"))
        .matches("[0-9a-f]{64}")
        .isNotEqualTo(keyed.identifier("a@b.test"))
        .isNotEqualTo(keyed.refreshToken("a@b.test"));
    assertThat(keyed.otp("id", "REGISTRATION", "123456"))
        .isNotEqualTo(keyed.otp("id", "PASSWORD_RESET", "123456"))
        .isNotEqualTo(keyed.otp("other", "REGISTRATION", "123456"));
  }

  @Test
  void digestComparison() {
    assertThat(KeyedHasher.sameDigest("abc", "abc")).isTrue();
    assertThat(KeyedHasher.sameDigest("abc", "abd")).isFalse();
    assertThat(KeyedHasher.sameDigest("abc", "abcd")).isFalse();
  }
}
