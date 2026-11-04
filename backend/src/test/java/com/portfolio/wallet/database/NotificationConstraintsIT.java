package com.portfolio.wallet.database;

import static com.portfolio.wallet.support.SqlAssertions.assertCheckViolation;
import static com.portfolio.wallet.support.SqlAssertions.assertPermissionDenied;
import static com.portfolio.wallet.support.SqlAssertions.assertRestrictViolation;
import static com.portfolio.wallet.support.SqlAssertions.assertUniqueViolation;
import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The email outbox table (V9; documentations/06 MVP-ADR-25): what a row reports never changes, the
 * status moves only PENDING to SENT or FAILED and then stays, attempts only grow, and the database
 * sets the times. Statements run as {@code wallet_app} unless they say otherwise.
 */
class NotificationConstraintsIT extends DatabaseIntegrationTest {

  private UUID newRow() throws SQLException {
    UUID eventId = UUID.randomUUID();
    insert(
        "notification.notifications",
        row("event_id", eventId, "user_id", UUID.randomUUID(), "kind", "KYC_APPROVED"));
    return eventId;
  }

  /** The row, with its time columns as instants. */
  private Map<String, Object> read(UUID eventId) {
    Map<String, Object> row =
        new java.util.HashMap<>(
            jdbc.queryForMap(
                "SELECT status, attempts, payload::text AS payload, occurred_at, next_attempt_at,"
                    + " sent_at, updated_at, last_failure FROM notification.notifications"
                    + " WHERE event_id = ?",
                eventId));
    row.replaceAll(
        (column, value) -> value instanceof java.sql.Timestamp time ? time.toInstant() : value);
    return row;
  }

  private void asOwnerRolledBack(SqlWork work) throws SQLException {
    try (Connection owner = ownerDataSource().getConnection()) {
      owner.setAutoCommit(false);
      try {
        work.run(owner);
      } finally {
        owner.rollback();
        owner.setAutoCommit(true);
      }
    }
  }

  // Insert ------------------------------------------------------------------------------------

  @Test
  void aRowStartsPendingWithNoAttemptAndTheDatabaseSetsItsTimes() throws SQLException {
    UUID eventId = UUID.randomUUID();
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          now[0] = transactionNow(c);
          insert(
              c,
              "notification.notifications",
              row(
                  "event_id", eventId,
                  "user_id", UUID.randomUUID(),
                  "kind", "WALLET_FROZEN",
                  "occurred_at", BACKDATED,
                  "next_attempt_at", BACKDATED,
                  "updated_at", BACKDATED,
                  "last_failure", "Planted"));
        });

    assertThat(read(eventId))
        .containsEntry("status", "PENDING")
        .containsEntry("attempts", 0)
        .containsEntry("payload", "{}")
        .containsEntry("occurred_at", now[0].toInstant())
        // N2: the outbox supplies the due time from the application clock; it is kept.
        .containsEntry("next_attempt_at", BACKDATED.toInstant())
        .containsEntry("updated_at", now[0].toInstant())
        .containsEntry("sent_at", null)
        .containsEntry("last_failure", null);
  }

  @Test
  void withoutADueTimeARowIsDueAtTheDatabasesNow() throws SQLException {
    UUID eventId = UUID.randomUUID();
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          now[0] = transactionNow(c);
          insert(
              c,
              "notification.notifications",
              row("event_id", eventId, "user_id", UUID.randomUUID(), "kind", "KYC_APPROVED"));
        });

    assertThat(read(eventId)).containsEntry("next_attempt_at", now[0].toInstant());
  }

  @Test
  void aRowCannotBeInsertedAsSentOrWithAttempts() {
    assertRestrictViolation(
        "a row is created PENDING with no attempt",
        () ->
            insert(
                "notification.notifications",
                row(
                    "event_id",
                    UUID.randomUUID(),
                    "user_id",
                    UUID.randomUUID(),
                    "kind",
                    "KYC_APPROVED",
                    "status",
                    "SENT")));
    assertRestrictViolation(
        "a row is created PENDING with no attempt",
        () ->
            insert(
                "notification.notifications",
                row(
                    "event_id",
                    UUID.randomUUID(),
                    "user_id",
                    UUID.randomUUID(),
                    "kind",
                    "KYC_APPROVED",
                    "attempts",
                    3)));
  }

  /** Only the business emails of MVP-ADR-25: an OTP can never be stored. */
  @ParameterizedTest
  @ValueSource(strings = {"OTP", "ACCOUNT_LOCKED", "EXISTING_ACCOUNT_NOTICE", "kyc_approved"})
  void onlyOutboxKindsAreAccepted(String kind) {
    assertCheckViolation(
        "ck_notifications_kind",
        () ->
            insert(
                "notification.notifications",
                row("event_id", UUID.randomUUID(), "user_id", UUID.randomUUID(), "kind", kind)));
  }

  @Test
  void theEventIdIsUniqueAndThePayloadAnObject() throws SQLException {
    UUID eventId = newRow();

    assertUniqueViolation(
        "pk_notifications",
        () ->
            insert(
                "notification.notifications",
                row("event_id", eventId, "user_id", UUID.randomUUID(), "kind", "KYC_REJECTED")));
    assertCheckViolation(
        "ck_notifications_payload_object",
        () ->
            jdbc.update(
                "INSERT INTO notification.notifications (event_id, user_id, kind, payload)"
                    + " VALUES (?, ?, 'KYC_REJECTED', '[]'::jsonb)",
                UUID.randomUUID(),
                UUID.randomUUID()));
  }

  /** The row holds a user id, never an address: the table has no column for one. */
  @Test
  void theTableHasNoAddressColumn() {
    assertThat(
            jdbc.queryForList(
                "SELECT attname FROM pg_attribute"
                    + " WHERE attrelid = 'notification.notifications'::regclass AND attnum > 0"
                    + " AND NOT attisdropped",
                String.class))
        .containsExactlyInAnyOrder(
            "event_id",
            "user_id",
            "kind",
            "payload",
            "status",
            "attempts",
            "next_attempt_at",
            "last_failure",
            "occurred_at",
            "sent_at",
            "updated_at");
  }

  // What was reported never changes ---------------------------------------------------------

  @ParameterizedTest
  @ValueSource(strings = {"event_id", "user_id", "kind", "payload", "occurred_at", "sent_at"})
  void theRuntimeRoleCannotUpdateWhatWasReported(String column) throws SQLException {
    UUID eventId = newRow();

    assertPermissionDenied(
        () ->
            jdbc.update(
                "UPDATE notification.notifications SET "
                    + column
                    + " = "
                    + column
                    + " WHERE event_id = ?",
                eventId));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "user_id = gen_random_uuid()",
        "kind = 'KYC_REJECTED'",
        "payload = '{\"x\":\"y\"}'::jsonb",
        "occurred_at = occurred_at - interval '1 day'",
        "event_id = gen_random_uuid()"
      })
  void theGuardKeepsWhatWasReportedEvenForTheOwner(String change) throws SQLException {
    UUID eventId = newRow();

    asOwnerRolledBack(
        owner ->
            assertRestrictViolation(
                "event id, recipient, kind, payload and time cannot change",
                () ->
                    update(
                        owner,
                        "UPDATE notification.notifications SET " + change + " WHERE event_id = ?",
                        eventId)));
  }

  // Status ------------------------------------------------------------------------------------

  @Test
  void aPendingRowCanBeRetriedAndThenSentWhichStampsTheSendTime() throws SQLException {
    UUID eventId = newRow();
    jdbc.update(
        "UPDATE notification.notifications SET attempts = 1,"
            + " next_attempt_at = now() + interval '1 minute', last_failure = 'X'"
            + " WHERE event_id = ?",
        eventId);
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          now[0] = transactionNow(c);
          update(
              c,
              "UPDATE notification.notifications SET status = 'SENT' WHERE event_id = ?",
              eventId);
        });

    assertThat(read(eventId))
        .containsEntry("status", "SENT")
        .containsEntry("attempts", 1)
        .containsEntry("sent_at", now[0].toInstant())
        .containsEntry("updated_at", now[0].toInstant());
  }

  /** SENT and FAILED are final, so such a row can never be sent again; for every role. */
  @ParameterizedTest
  @ValueSource(strings = {"SENT", "FAILED"})
  void aSentOrFailedRowCanNoLongerChangeEvenForTheOwner(String finalStatus) throws SQLException {
    UUID eventId = newRow();
    jdbc.update(
        "UPDATE notification.notifications SET status = ?, attempts = 1 WHERE event_id = ?",
        finalStatus,
        eventId);

    for (String change :
        new String[] {"status = 'PENDING'", "attempts = attempts + 1", "status = status"}) {
      assertRestrictViolation(
          "can no longer change",
          () ->
              jdbc.update(
                  "UPDATE notification.notifications SET " + change + " WHERE event_id = ?",
                  eventId));
    }
    asOwnerRolledBack(
        owner ->
            assertRestrictViolation(
                "can no longer change",
                () ->
                    update(
                        owner,
                        "UPDATE notification.notifications SET status = 'PENDING', sent_at = NULL"
                            + " WHERE event_id = ?",
                        eventId)));
    assertThat(read(eventId)).containsEntry("status", finalStatus);
  }

  @Test
  void attemptsOnlyGrowAndTheStatusIsADocumentedValue() throws SQLException {
    UUID eventId = newRow();
    jdbc.update("UPDATE notification.notifications SET attempts = 2 WHERE event_id = ?", eventId);

    assertRestrictViolation(
        "attempts cannot decrease",
        () ->
            jdbc.update(
                "UPDATE notification.notifications SET attempts = 1 WHERE event_id = ?", eventId));
    assertCheckViolation(
        "ck_notifications_status",
        () ->
            jdbc.update(
                "UPDATE notification.notifications SET status = 'RETRYING' WHERE event_id = ?",
                eventId));
  }
}
