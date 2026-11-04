package com.portfolio.wallet.user.persistence;

import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code usr.customers}. Plain SQL, like the auth module (documentations/06 MVP-ADR-10 amendment):
 * the per-column grants and the database-set columns stay visible in each statement.
 */
@Repository
public class CustomerRepository {

  private final JdbcClient jdbc;

  public CustomerRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Idempotent and race-safe: a second or concurrent call does nothing. Only {@code user_id} may be
   * inserted (V8 grants INSERT on that column alone).
   */
  public void insertIfAbsent(UUID userId) {
    jdbc.sql("INSERT INTO usr.customers (user_id) VALUES (:id) ON CONFLICT DO NOTHING")
        .param("id", userId)
        .update();
  }

  public Optional<CustomerRow> find(UUID userId) {
    return find(userId, "");
  }

  /**
   * Locks the profile for an update, so a concurrent approval (which copies the legal name into
   * this row) and a concurrent submit (V8's {@code FOR SHARE}) are serialised with it. {@code FOR
   * NO KEY UPDATE} (N5): the key never changes, so inserting an application, whose foreign key
   * takes {@code FOR KEY SHARE} on this row, does not wait for it. Needs UPDATE on a column, which
   * V8 grants.
   */
  public Optional<CustomerRow> findForUpdate(UUID userId) {
    return find(userId, " FOR NO KEY UPDATE");
  }

  // The suffix is one of this class's constants, never client input.
  private Optional<CustomerRow> find(UUID userId, String lockClause) {
    return jdbc.sql(
            """
            SELECT user_id, first_name, last_name, date_of_birth, country_code, created_at,
                   updated_at
            FROM usr.customers WHERE user_id = :id
            """
                + lockClause)
        .param("id", userId)
        .query(
            (rs, row) ->
                new CustomerRow(
                    rs.getObject("user_id", UUID.class),
                    rs.getString("first_name"),
                    rs.getString("last_name"),
                    rs.getObject("date_of_birth", LocalDate.class),
                    rs.getString("country_code"),
                    Timestamps.read(rs, "created_at"),
                    Timestamps.read(rs, "updated_at")))
        .optional();
  }

  /**
   * Copies the approved legal name into the profile, once: the condition and V8's trigger both
   * refuse a second write. V8 accepts it only after the application carrying this name was approved
   * in the same transaction (J4), so call it after {@link KycApplicationRepository#approve}.
   *
   * @return 1, or 0 if the legal name is set already
   */
  public int setLegalName(UUID userId, String firstName, String lastName) {
    return jdbc.sql(
            """
            UPDATE usr.customers SET first_name = :first, last_name = :last
            WHERE user_id = :id AND first_name IS NULL
            """)
        .param("first", firstName)
        .param("last", lastName)
        .param("id", userId)
        .update();
  }

  public void updateProfile(UUID userId, LocalDate dateOfBirth, String countryCode) {
    jdbc.sql(
            """
            UPDATE usr.customers SET date_of_birth = :dob, country_code = :country
            WHERE user_id = :id
            """)
        .param("dob", dateOfBirth)
        .param("country", countryCode)
        .param("id", userId)
        .update();
  }
}
