package com.portfolio.wallet.audit.application;

import java.net.InetAddress;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * One audit record (documentations/03 §7.2). The time and the correlation id are not part of it:
 * the database sets the first and {@link AuditService} reads the second from the request.
 *
 * <p>Old and new values are explicit maps, so a caller has to name each value it records, and they
 * are checked here, because they must never contain hashes, tokens, OTPs, passwords or KYC content
 * (doc 07 §9.3). A key that suggests such content is a programming error and throws {@link
 * IllegalArgumentException}. Name what is recorded ({@code status}, {@code reason}) rather than a
 * secret ({@code tokenRevoked}).
 */
public record AuditEntry(
    UUID actorId,
    ActorType actorType,
    String action,
    String resource,
    String resourceId,
    AuditResult result,
    InetAddress clientIp,
    Map<String, Object> oldValue,
    Map<String, Object> newValue) {

  private static final int MAX_ACTION_OR_RESOURCE = 60;
  private static final int MAX_RESOURCE_ID = 64;

  /**
   * Matched against keys lower-cased, with everything but letters removed. The last group is KYC
   * and profile content (doc 03 §5.1; documentations/08 §4B.4 AC 11).
   */
  private static final List<String> FORBIDDEN_KEY_PARTS =
      List.of(
          "password",
          "passwd",
          "hash",
          "token",
          "otp",
          "secret",
          "pepper",
          "privatekey",
          "kyc",
          "documentref",
          "firstname",
          "lastname",
          "legalname",
          "fullname",
          "dateofbirth",
          "birthdate");

  public AuditEntry {
    Objects.requireNonNull(actorType, "actorType");
    Objects.requireNonNull(result, "result");
    requireLength("action", action, MAX_ACTION_OR_RESOURCE);
    requireLength("resource", resource, MAX_ACTION_OR_RESOURCE);
    if (resourceId != null) {
      requireLength("resourceId", resourceId, MAX_RESOURCE_ID);
    }
    oldValue = checkedCopy(oldValue);
    newValue = checkedCopy(newValue);
  }

  public static AuditEntry of(
      ActorType actorType, String action, String resource, AuditResult result) {
    return new AuditEntry(null, actorType, action, resource, null, result, null, null, null);
  }

  public AuditEntry withActor(UUID newActorId) {
    return new AuditEntry(
        newActorId, actorType, action, resource, resourceId, result, clientIp, oldValue, newValue);
  }

  public AuditEntry withResourceId(String newResourceId) {
    return new AuditEntry(
        actorId, actorType, action, resource, newResourceId, result, clientIp, oldValue, newValue);
  }

  public AuditEntry withClientIp(InetAddress newClientIp) {
    return new AuditEntry(
        actorId, actorType, action, resource, resourceId, result, newClientIp, oldValue, newValue);
  }

  public AuditEntry withOldValue(Map<String, Object> value) {
    return new AuditEntry(
        actorId, actorType, action, resource, resourceId, result, clientIp, value, newValue);
  }

  public AuditEntry withNewValue(Map<String, Object> value) {
    return new AuditEntry(
        actorId, actorType, action, resource, resourceId, result, clientIp, oldValue, value);
  }

  private static void requireLength(String field, String value, int max) {
    if (value == null || value.isBlank() || value.length() > max) {
      throw new IllegalArgumentException(field + " must have 1 to " + max + " characters");
    }
  }

  private static Map<String, Object> checkedCopy(Map<String, Object> value) {
    if (value == null) {
      return null;
    }
    checkValue(value);
    return Collections.unmodifiableMap(new LinkedHashMap<>(value));
  }

  private static void checkValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      map.forEach(
          (key, nested) -> {
            checkKey(String.valueOf(key));
            checkValue(nested);
          });
    } else if (value instanceof Collection<?> collection) {
      collection.forEach(AuditEntry::checkValue);
    } else if (value instanceof String text && text.startsWith("$argon2")) {
      throw new IllegalArgumentException("Audit values must not contain a password hash");
    }
  }

  private static void checkKey(String key) {
    String normalised = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
    if (FORBIDDEN_KEY_PARTS.stream().anyMatch(normalised::contains)) {
      // The key is a constant in the caller's code, so naming it discloses nothing.
      throw new IllegalArgumentException("Audit values must not contain sensitive key: " + key);
    }
  }
}
