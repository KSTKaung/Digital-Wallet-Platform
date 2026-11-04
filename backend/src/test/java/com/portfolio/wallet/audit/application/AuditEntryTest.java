package com.portfolio.wallet.audit.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Audit values never hold hashes, tokens, OTPs or passwords (documentations/07 §9.3). */
class AuditEntryTest {

  private static AuditEntry entry() {
    return AuditEntry.of(ActorType.CUSTOMER, "LOGIN", "USER", AuditResult.SUCCESS);
  }

  /** OTP_VERIFY rows carry this, so they can be linked to an account without naming it. */
  @Test
  void aKeyedIdentifierDigestIsAllowedAsTheResourceIdButSecretsStayRefused() {
    String digest = "ab".repeat(32);

    assertThat(entry().withResourceId(digest).resourceId()).isEqualTo(digest);

    assertThatThrownBy(() -> entry().withNewValue(Map.of("otp", "123456")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> entry().withNewValue(Map.of("identifierHash", digest)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> entry().withNewValue(Map.of("status", "$argon2id$v=19$x")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void buildsAFullEntry() throws Exception {
    UUID actor = UUID.randomUUID();
    InetAddress ip = InetAddress.getByName("192.0.2.1");

    AuditEntry entry =
        entry()
            .withActor(actor)
            .withResourceId("id-1")
            .withClientIp(ip)
            .withOldValue(Map.of("status", "PENDING_VERIFICATION"))
            .withNewValue(Map.of("status", "ACTIVE"));

    assertThat(entry.actorId()).isEqualTo(actor);
    assertThat(entry.resourceId()).isEqualTo("id-1");
    assertThat(entry.clientIp()).isEqualTo(ip);
    assertThat(entry.oldValue()).containsEntry("status", "PENDING_VERIFICATION");
    assertThat(entry.newValue()).containsEntry("status", "ACTIVE");
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "password",
        "passwordHash",
        "PASSWORD_HASH",
        "new-password",
        "passwd",
        "token",
        "refreshToken",
        "token_hash",
        "resetToken",
        "otp",
        "otpHash",
        "secret",
        "pepper",
        "privateKey",
        "kycDocument",
        "documentReference",
        "document_reference",
        "firstName",
        "last_name",
        "legalName",
        "fullName",
        "dateOfBirth",
        "birthDate"
      })
  void sensitiveKeyIsRejected(String key) {
    assertThatThrownBy(() -> entry().withNewValue(Map.of(key, "x")))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> entry().withOldValue(Map.of(key, "x")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void sensitiveKeyIsRejectedInNestedValues() {
    assertThatThrownBy(() -> entry().withNewValue(Map.of("a", Map.of("otp", "123456"))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> entry().withNewValue(Map.of("a", List.of(Map.of("token", "x")))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void passwordHashValueIsRejectedUnderAnyKey() {
    assertThatThrownBy(() -> entry().withNewValue(Map.of("note", "$argon2id$v=19$m=19456")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageNotContaining("argon2id$v");
  }

  @Test
  void valueIsCopiedSoALaterChangeCannotBypassTheCheck() {
    Map<String, Object> source = new HashMap<>(Map.of("status", "ACTIVE"));

    AuditEntry entry = entry().withNewValue(source);
    source.put("otp", "123456");

    assertThat(entry.newValue()).containsOnlyKeys("status");
  }

  @Test
  void requiredFieldsAndColumnLengthsAreChecked() {
    assertThatThrownBy(() -> AuditEntry.of(null, "A", "R", AuditResult.SUCCESS))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> AuditEntry.of(ActorType.SYSTEM, "A", "R", null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> AuditEntry.of(ActorType.SYSTEM, " ", "R", AuditResult.SUCCESS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> AuditEntry.of(ActorType.SYSTEM, "A".repeat(61), "R", AuditResult.SUCCESS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> AuditEntry.of(ActorType.SYSTEM, "A", "R".repeat(61), AuditResult.SUCCESS))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> entry().withResourceId("x".repeat(65)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
