package com.portfolio.wallet.user.persistence;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code usr.kyc_applications}. Every read and write is scoped by the owning customer, so an id
 * that belongs to someone else behaves exactly like one that does not exist (MVP-ADR-19).
 */
@Repository
public class KycApplicationRepository {

  private static final String COLUMNS =
      """
      SELECT id, customer_id, verification_level, document_type, document_reference, first_name,
             last_name, date_of_birth, country_code, status, submitted_at, decided_at,
             reviewer_id, rejection_reason, created_at, updated_at
      FROM usr.kyc_applications
      """;

  private static final RowMapper<KycApplicationRow> MAPPER =
      (rs, row) ->
          new KycApplicationRow(
              rs.getObject("id", UUID.class),
              rs.getObject("customer_id", UUID.class),
              rs.getString("verification_level"),
              rs.getString("document_type"),
              rs.getString("document_reference"),
              rs.getString("first_name"),
              rs.getString("last_name"),
              rs.getObject("date_of_birth", LocalDate.class),
              rs.getString("country_code"),
              rs.getString("status"),
              Timestamps.read(rs, "submitted_at"),
              Timestamps.read(rs, "decided_at"),
              rs.getObject("reviewer_id", UUID.class),
              rs.getString("rejection_reason"),
              Timestamps.read(rs, "created_at"),
              Timestamps.read(rs, "updated_at"));

  private final JdbcClient jdbc;

  public KycApplicationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Inserts a {@code DRAFT}, unless the customer already has an active application. The partial
   * unique index decides, so two concurrent calls cannot both succeed: the second waits for the
   * first to commit and then returns empty, without raising a unique violation.
   */
  public Optional<UUID> insertDraftIfNoneActive(
      UUID customerId,
      String verificationLevel,
      String documentType,
      String documentReference,
      String firstName,
      String lastName) {
    return jdbc.sql(
            """
            INSERT INTO usr.kyc_applications
              (customer_id, verification_level, document_type, document_reference, first_name,
               last_name)
            VALUES (:customer, :level, :type, :reference, :first, :last)
            ON CONFLICT (customer_id) WHERE status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED')
            DO NOTHING
            RETURNING id
            """)
        .param("customer", customerId)
        .param("level", verificationLevel)
        .param("type", documentType)
        .param("reference", documentReference)
        .param("first", firstName)
        .param("last", lastName)
        .query(UUID.class)
        .optional();
  }

  public Optional<KycApplicationRow> findOwned(UUID id, UUID customerId) {
    return jdbc.sql(COLUMNS + " WHERE id = :id AND customer_id = :customer")
        .param("id", id)
        .param("customer", customerId)
        .query(MAPPER)
        .optional();
  }

  /**
   * Locks the application for a state change. Like every row lock this needs UPDATE on at least one
   * column, which V8 grants.
   */
  public Optional<KycApplicationRow> findOwnedForUpdate(UUID id, UUID customerId) {
    return jdbc.sql(COLUMNS + " WHERE id = :id AND customer_id = :customer FOR UPDATE")
        .param("id", id)
        .param("customer", customerId)
        .query(MAPPER)
        .optional();
  }

  /**
   * The application to report as the customer's KYC status: the active one if there is one,
   * otherwise the latest rejected one.
   */
  public Optional<KycApplicationRow> findCurrent(UUID customerId) {
    return jdbc.sql(
            COLUMNS
                + """
                 WHERE customer_id = :customer
                ORDER BY (status <> 'REJECTED') DESC, created_at DESC
                LIMIT 1
                """)
        .param("customer", customerId)
        .query(MAPPER)
        .optional();
  }

  /**
   * Whether an application locks the profile's date of birth and country: one is {@code
   * PENDING_REVIEW} or {@code APPROVED} (owner, 2026-11-03). The same rule as V8's trigger.
   */
  public boolean locksProfile(UUID customerId) {
    return jdbc.sql(
            """
            SELECT EXISTS (SELECT 1 FROM usr.kyc_applications
                           WHERE customer_id = :customer
                             AND status IN ('PENDING_REVIEW', 'APPROVED'))
            """)
        .param("customer", customerId)
        .query(Boolean.class)
        .single();
  }

  /** Runs under {@link #findOwnedForUpdate}; the status condition is a second guard. */
  public void updateDraft(
      UUID id,
      UUID customerId,
      String verificationLevel,
      String documentType,
      String documentReference,
      String firstName,
      String lastName) {
    jdbc.sql(
            """
            UPDATE usr.kyc_applications
            SET verification_level = :level, document_type = :type,
                document_reference = :reference, first_name = :first, last_name = :last
            WHERE id = :id AND customer_id = :customer AND status = 'DRAFT'
            """)
        .param("level", verificationLevel)
        .param("type", documentType)
        .param("reference", documentReference)
        .param("first", firstName)
        .param("last", lastName)
        .param("id", id)
        .param("customer", customerId)
        .update();
  }

  /** The database sets {@code submitted_at} (V8). */
  public void submit(UUID id, UUID customerId) {
    jdbc.sql(
            """
            UPDATE usr.kyc_applications SET status = 'PENDING_REVIEW'
            WHERE id = :id AND customer_id = :customer AND status = 'DRAFT'
            """)
        .param("id", id)
        .param("customer", customerId)
        .update();
  }

  // Staff reads and decisions ----------------------------------------------------------------
  // Staff see submitted applications only: a draft is the customer's unsent work, so for staff it
  // does not exist (the same 404 as a missing id).

  /**
   * One page of submitted applications in {@code status}, oldest submission first, then by id;
   * {@code ix_kyc_applications_status_submitted} serves the filter and the sort (K3).
   */
  public List<KycApplicationRow> findSubmittedPage(String status, int limit, long offset) {
    return jdbc.sql(
            COLUMNS
                + """
                 WHERE status = :status AND status <> 'DRAFT'
                ORDER BY submitted_at, id
                LIMIT :limit OFFSET :offset
                """)
        .param("status", status)
        .param("limit", limit)
        .param("offset", offset)
        .query(MAPPER)
        .list();
  }

  public long countSubmitted(String status) {
    return jdbc.sql(
            """
            SELECT count(*) FROM usr.kyc_applications
            WHERE status = :status AND status <> 'DRAFT'
            """)
        .param("status", status)
        .query(Long.class)
        .single();
  }

  public Optional<KycApplicationRow> findSubmitted(UUID id) {
    return jdbc.sql(COLUMNS + " WHERE id = :id AND status <> 'DRAFT'")
        .param("id", id)
        .query(MAPPER)
        .optional();
  }

  /** Locks a submitted application for a decision: the first lock of an approval or rejection. */
  public Optional<KycApplicationRow> findSubmittedForUpdate(UUID id) {
    return jdbc.sql(COLUMNS + " WHERE id = :id AND status <> 'DRAFT' FOR UPDATE")
        .param("id", id)
        .query(MAPPER)
        .optional();
  }

  /**
   * Runs under {@link #findSubmittedForUpdate}; the status condition is a second guard, and V8's
   * trigger sets {@code decided_at}.
   *
   * @return 1, or 0 if the application is no longer {@code PENDING_REVIEW}
   */
  public int approve(UUID id, UUID reviewerId) {
    return jdbc.sql(
            """
            UPDATE usr.kyc_applications SET status = 'APPROVED', reviewer_id = :reviewer
            WHERE id = :id AND status = 'PENDING_REVIEW'
            """)
        .param("reviewer", reviewerId)
        .param("id", id)
        .update();
  }

  /** As {@link #approve}, with the reason the customer will see. */
  public int reject(UUID id, UUID reviewerId, String reason) {
    return jdbc.sql(
            """
            UPDATE usr.kyc_applications
            SET status = 'REJECTED', reviewer_id = :reviewer, rejection_reason = :reason
            WHERE id = :id AND status = 'PENDING_REVIEW'
            """)
        .param("reviewer", reviewerId)
        .param("reason", reason)
        .param("id", id)
        .update();
  }
}
