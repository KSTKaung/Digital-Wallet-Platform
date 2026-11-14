package com.portfolio.wallet.transfer.application;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.UUID;

/**
 * The outbox event id of one review reminder: deterministic in (transfer, officer), so every run
 * and every instance computes the same id and the outbox writes it at most once (documentations/06
 * MVP-ADR-24 "Jobs (Q11)", MVP-ADR-25 "Money emails"; doc 08 §4C.3).
 *
 * <p>A name-based UUID, version 5 (RFC 9562 §5.5): SHA-1 over a namespace of this application and
 * the name {@code TRANSFER_REVIEW_EXPIRING:<transfer id>:<officer id>}. SHA-1 serves only to spread
 * the name over the id, as the RFC defines; the id is no secret and protects nothing. Its version
 * digit (5) keeps it apart from every random (version 4) id the outbox generates.
 */
final class ReviewReminderEventIds {

  /** The namespace of this application's name-based event ids; fixed, never to change. */
  static final UUID NAMESPACE = UUID.fromString("22cb88ea-f555-4398-8bd2-89731cfaada4");

  private static final String KIND = "TRANSFER_REVIEW_EXPIRING";

  private ReviewReminderEventIds() {}

  static UUID of(UUID transferId, UUID officerId) {
    Objects.requireNonNull(transferId, "transferId");
    Objects.requireNonNull(officerId, "officerId");
    return nameBased(NAMESPACE, KIND + ":" + transferId + ":" + officerId);
  }

  /** RFC 9562 version 5. */
  static UUID nameBased(UUID namespace, String name) {
    MessageDigest sha1;
    try {
      sha1 = MessageDigest.getInstance("SHA-1");
    } catch (NoSuchAlgorithmException e) {
      // Every Java platform must provide SHA-1 (MessageDigest's documentation).
      throw new IllegalStateException("SHA-1 is not available", e);
    }
    sha1.update(
        ByteBuffer.allocate(16)
            .putLong(namespace.getMostSignificantBits())
            .putLong(namespace.getLeastSignificantBits())
            .array());
    sha1.update(name.getBytes(StandardCharsets.UTF_8));
    ByteBuffer hash = ByteBuffer.wrap(sha1.digest());
    long msb = hash.getLong();
    long lsb = hash.getLong();
    msb = (msb & ~0xF000L) | 0x5000L; // version 5
    lsb = (lsb & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L; // RFC variant
    return new UUID(msb, lsb);
  }
}
