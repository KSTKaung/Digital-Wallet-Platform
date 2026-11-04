package com.portfolio.wallet.auth.persistence;

import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.auth.domain.UserStatus;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code auth.users}. The auth module uses plain SQL throughout: V7's per-column grants and the
 * atomic, conditional statements the limits depend on are clearer here than behind entity state.
 */
@Repository
public class UserRepository {

  private final JdbcClient jdbc;
  private final DataSource dataSource;

  public UserRepository(JdbcClient jdbc, DataSource dataSource) {
    this.jdbc = jdbc;
    this.dataSource = dataSource;
  }

  /**
   * Conflict-safe: a taken email or phone returns empty, also in a race, and no unique violation is
   * raised.
   */
  public Optional<UUID> insertCustomerIfAbsent(String email, String phoneNumber) {
    return jdbc.sql(
            """
            INSERT INTO auth.users (email, phone_number, status, role)
            VALUES (:email, :phone, :status, :role)
            ON CONFLICT DO NOTHING
            RETURNING id
            """)
        .param("email", email)
        .param("phone", phoneNumber)
        .param("status", UserStatus.PENDING_VERIFICATION.name())
        .param("role", Role.CUSTOMER.name())
        .query(UUID.class)
        .optional();
  }

  /** An {@code ACTIVE} account with no phone; same conflict behaviour as for a customer. */
  public Optional<UUID> insertStaffIfAbsent(String email, Role role) {
    return jdbc.sql(
            """
            INSERT INTO auth.users (email, status, role)
            VALUES (:email, :status, :role)
            ON CONFLICT DO NOTHING
            RETURNING id
            """)
        .param("email", email)
        .param("status", UserStatus.ACTIVE.name())
        .param("role", role.name())
        .query(UUID.class)
        .optional();
  }

  /**
   * Serialises every claim on one phone number until the transaction ends. A number that reads as
   * free under this lock therefore stays free, so moving a pending account to it cannot raise a
   * unique violation.
   */
  public void lockPhone(String phoneNumber) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))")
        .param("key", "phone:" + phoneNumber)
        .query((rs, row) -> Boolean.TRUE)
        .list();
  }

  /**
   * Locks the account against a concurrent re-registration, verification or purge. NO KEY: foreign
   * keys pointing at another account's row, such as a notice claim, never wait on this.
   */
  public Optional<LockedUser> lockByEmail(String email) {
    return jdbc.sql("SELECT id, status FROM auth.users WHERE email = :email FOR NO KEY UPDATE")
        .param("email", email)
        .query(
            (rs, row) ->
                new LockedUser(
                    rs.getObject("id", UUID.class), UserStatus.valueOf(rs.getString("status"))))
        .optional();
  }

  /** Runs under {@link #lockPhone} for the new number and {@link #lockByEmail} for the row. */
  public void changePhone(UUID id, String phoneNumber) {
    jdbc.sql("UPDATE auth.users SET phone_number = :phone WHERE id = :id")
        .param("phone", phoneNumber)
        .param("id", id)
        .update();
  }

  /**
   * Deletes accounts nobody verified in time, with their credentials by the foreign keys. It never
   * waits on a row lock: a row being registered or verified is skipped, and goes on a later run if
   * it still qualifies. An account with a session or a reset token is left alone, because its
   * foreign keys would fail the whole statement. {@code created_at} is the database's clock, so the
   * cutoff is too, and a re-registration does not extend it: a pending account can hold a phone
   * number for one lifetime only. The status is written out so that the plan can always use the
   * partial index, which a bound parameter would not guarantee.
   */
  public int deleteExpiredPending(Duration lifetime) {
    return jdbc.sql(
            """
            DELETE FROM auth.users
            WHERE id IN (
              SELECT u.id FROM auth.users u
              WHERE u.status = 'PENDING_VERIFICATION'
                AND u.created_at <= now() - make_interval(secs => CAST(:seconds AS double precision))
                AND NOT EXISTS (SELECT 1 FROM auth.sessions s WHERE s.user_id = u.id)
                AND NOT EXISTS (SELECT 1 FROM auth.password_reset_tokens t WHERE t.user_id = u.id)
              FOR UPDATE OF u SKIP LOCKED)
            """)
        .param("seconds", lifetime.toSeconds())
        .update();
  }

  /**
   * Keeps the owner from being purged until the transaction ends, so a notice can reference it. If
   * the purge is deleting the owner this waits, and then finds no owner. KEY SHARE: it does not
   * wait on a verification or a re-registration of that account, unless that one is moving its
   * phone. Like every row lock this needs UPDATE on at least one column of the table, not only
   * SELECT.
   */
  public Optional<AccountRow> lockOwnerOfPhone(String phoneNumber) {
    return findAccount("u.phone_number = :value FOR KEY SHARE OF u", phoneNumber);
  }

  public Optional<AccountRow> findAccountByEmail(String email) {
    return findAccount("u.email = :value", email);
  }

  public Optional<AccountRow> findAccountById(UUID id) {
    return findAccount("u.id = :value", id);
  }

  /**
   * @param checkedHash the hash the caller's password was verified against; a re-registration may
   *     have replaced it since
   * @return whether a pending account was activated
   */
  public boolean activatePending(String email, String checkedHash) {
    return jdbc.sql(
                """
                UPDATE auth.users u SET status = :active
                WHERE u.email = :email AND u.status = :pending
                  AND EXISTS (SELECT 1 FROM auth.credentials c
                              WHERE c.user_id = u.id AND c.password_hash = :checked)
                """)
            .param("active", UserStatus.ACTIVE.name())
            .param("email", email)
            .param("pending", UserStatus.PENDING_VERIFICATION.name())
            .param("checked", checkedHash)
            .update()
        == 1;
  }

  /** Email and phone of one account, for the profile view of another module (PR 4a). */
  public Optional<ContactRow> findContacts(UUID id) {
    return jdbc.sql("SELECT email, phone_number FROM auth.users WHERE id = :id")
        .param("id", id)
        .query((rs, row) -> new ContactRow(rs.getString("email"), rs.getString("phone_number")))
        .optional();
  }

  /**
   * As {@link #findContacts(UUID)}, with a per-statement query timeout (S1): the JDBC driver
   * cancels the statement after {@code timeout}, rounded up to whole seconds, and the call throws a
   * {@code DataAccessException}. A statement timeout, not {@code SET LOCAL statement_timeout},
   * because the email outbox calls this with no transaction open. Inside a transaction with its own
   * timeout, the shorter of the two applies.
   */
  public Optional<ContactRow> findContacts(UUID id, Duration timeout) {
    JdbcTemplate template = new JdbcTemplate(dataSource);
    template.setQueryTimeout((int) Math.max(1, (timeout.toMillis() + 999) / 1000));
    return template
        .query(
            "SELECT email, phone_number FROM auth.users WHERE id = ?",
            (rs, row) -> new ContactRow(rs.getString("email"), rs.getString("phone_number")),
            id)
        .stream()
        .findFirst();
  }

  // The condition is one of this class's constants, never client input.
  private Optional<AccountRow> findAccount(String condition, Object value) {
    return jdbc.sql(
            """
            SELECT u.id, u.email, u.status, u.role, c.password_hash, c.locked_until
            FROM auth.users u
            JOIN auth.credentials c ON c.user_id = u.id
            WHERE
            """
                + condition)
        .param("value", value)
        .query(
            (rs, row) ->
                new AccountRow(
                    rs.getObject("id", UUID.class),
                    rs.getString("email"),
                    UserStatus.valueOf(rs.getString("status")),
                    Role.valueOf(rs.getString("role")),
                    rs.getString("password_hash"),
                    Timestamps.read(rs, "locked_until")))
        .optional();
  }
}
