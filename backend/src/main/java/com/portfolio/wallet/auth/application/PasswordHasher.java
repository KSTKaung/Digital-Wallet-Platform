package com.portfolio.wallet.auth.application;

import com.portfolio.wallet.auth.domain.PasswordPolicy;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id (documentations/06 MVP-ADR-23). Passwords are NFC-normalised first. A semaphore bounds
 * the hashes computed at the same time, dummy hashes included, which bounds the memory they use.
 * Never call this inside a database transaction: a queued hash would hold a connection.
 */
@Component
public class PasswordHasher {

  private static final String EMPTY_STAND_IN = "-";

  private final Argon2PasswordEncoder encoder;
  private final Semaphore permits;
  private final String dummyHash;

  public PasswordHasher(AuthProperties properties) {
    AuthProperties.Argon2 argon2 = properties.argon2();
    this.encoder =
        new Argon2PasswordEncoder(
            argon2.saltLengthBytes(),
            argon2.hashLengthBytes(),
            argon2.parallelism(),
            argon2.memoryKib(),
            argon2.iterations());
    this.permits = new Semaphore(argon2.maxConcurrentHashes(), true);
    this.dummyHash = encoder.encode(UUID.randomUUID().toString());
  }

  public String hash(String password) {
    return bounded(() -> encoder.encode(PasswordPolicy.normalize(password)));
  }

  /**
   * An empty password never matches, but still costs a full computation: the encoder returns at
   * once for an empty input, which would let timing reveal which refusal path a request took.
   */
  public boolean matches(String password, String hash) {
    String normalized = PasswordPolicy.normalize(password);
    if (normalized.isEmpty()) {
      bounded(() -> encoder.matches(EMPTY_STAND_IN, hash));
      return false;
    }
    return bounded(() -> encoder.matches(normalized, hash));
  }

  /** Costs the same as {@link #matches}, for accounts whose password must not be checked. */
  public void dummyVerify(String password) {
    matches(password, dummyHash);
  }

  private <T> T bounded(Supplier<T> work) {
    try {
      permits.acquire();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Interrupted while waiting to hash", e);
    }
    try {
      return work.get();
    } finally {
      permits.release();
    }
  }
}
