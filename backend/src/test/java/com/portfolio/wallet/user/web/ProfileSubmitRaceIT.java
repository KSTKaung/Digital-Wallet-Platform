package com.portfolio.wallet.user.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * A profile update and a submit at the same time (owner follow-up #2, 2026-11-03): changed data
 * must never sit under a {@code PENDING_REVIEW} application. A test transaction holds the profile
 * row, which both requests need; each is seen waiting before the next starts, and PostgreSQL grants
 * a row lock to its waiters in order, so the order is the test's, not chance.
 */
class ProfileSubmitRaceIT extends UserFlowSupport {

  private static final long TIMEOUT_SECONDS = 30;

  private String countryOf(UUID customer) {
    return jdbc.queryForObject(
        "SELECT country_code FROM usr.customers WHERE user_id = ?", String.class, customer);
  }

  private String statusOf(UUID application) {
    return jdbc.queryForObject(
        "SELECT status FROM usr.kyc_applications WHERE id = ?", String.class, application);
  }

  @Test
  void aProfileUpdateQueuedBehindASubmitIsRefused() throws Exception {
    Account account = customer();
    completeProfile(account);
    UUID application = draft(account);

    List<MockHttpServletResponse> responses =
        whileHoldingTheProfile(
            account.id(),
            () -> submit(application, account.token()),
            () -> putAs(PROFILE, profileBody("1985-01-01", "JP"), account.token()));

    assertThat(responses.get(0).getStatus()).isEqualTo(200);
    assertThat(responses.get(1).getStatus()).isEqualTo(409);
    assertThat(errorCode(responses.get(1))).isEqualTo("INVALID_KYC_STATE");
    assertThat(statusOf(application)).isEqualTo("PENDING_REVIEW");
    assertThat(countryOf(account.id())).isEqualTo("TH");
  }

  @Test
  void aSubmitQueuedBehindAProfileUpdateSubmitsTheUpdatedProfileWhichThenLocks() throws Exception {
    Account account = customer();
    completeProfile(account);
    UUID application = draft(account);

    List<MockHttpServletResponse> responses =
        whileHoldingTheProfile(
            account.id(),
            () -> putAs(PROFILE, profileBody("1985-01-01", "JP"), account.token()),
            () -> submit(application, account.token()));

    assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactly(200, 200);
    assertThat(statusOf(application)).isEqualTo("PENDING_REVIEW");
    assertThat(countryOf(account.id())).isEqualTo("JP");
    // K4, N4: the submit read the profile under its lock, so the snapshot holds the update.
    assertThat(
            jdbc.queryForMap(
                "SELECT date_of_birth::text AS dob, country_code FROM usr.kyc_applications"
                    + " WHERE id = ?",
                application))
        .containsEntry("dob", "1985-01-01")
        .containsEntry("country_code", "JP");
    // The profile the reviewer reads is the one that was submitted; it can no longer change.
    assertThat(
            jdbc.queryForObject(
                "SELECT c.updated_at <= k.submitted_at FROM usr.customers c"
                    + " JOIN usr.kyc_applications k ON k.customer_id = c.user_id WHERE k.id = ?",
                Boolean.class,
                application))
        .isTrue();
    assertThat(putAs(PROFILE, profileBody("1985-01-01", "TH"), account.token()).getStatus())
        .isEqualTo(409);
  }

  /**
   * Owner purpose (2026-11-04, N4): date of birth and country cannot change after submission, also
   * with several requests at once. A submit and then three profile updates queue on the profile:
   * the submit wins, every update is refused, and the profile and the snapshot stay as submitted.
   */
  @Test
  void manyProfileUpdatesQueuedBehindASubmitAreAllRefused() throws Exception {
    Account account = customer();
    completeProfile(account);
    UUID application = draft(account);
    List<Callable<MockHttpServletResponse>> requests = new ArrayList<>();
    requests.add(() -> submit(application, account.token()));
    for (String country : List.of("JP", "GB", "US")) {
      requests.add(() -> putAs(PROFILE, profileBody("1985-01-01", country), account.token()));
    }

    List<MockHttpServletResponse> responses = whileHoldingTheProfile(account.id(), requests);

    assertThat(responses.getFirst().getStatus()).isEqualTo(200);
    assertThat(responses.subList(1, responses.size()))
        .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(409));
    assertThat(countryOf(account.id())).isEqualTo("TH");
    assertThat(
            jdbc.queryForObject(
                "SELECT country_code FROM usr.kyc_applications WHERE id = ?",
                String.class,
                application))
        .isEqualTo("TH");
  }

  private List<MockHttpServletResponse> whileHoldingTheProfile(
      UUID customer,
      Callable<MockHttpServletResponse> first,
      Callable<MockHttpServletResponse> second)
      throws Exception {
    return whileHoldingTheProfile(customer, List.of(first, second));
  }

  /** Starts each request only once the previous ones wait on the held profile row, in order. */
  private List<MockHttpServletResponse> whileHoldingTheProfile(
      UUID customer, List<Callable<MockHttpServletResponse>> requests) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(requests.size());
    try (Connection holder = openTransaction()) {
      queryOne(
          holder,
          "SELECT user_id FROM usr.customers WHERE user_id = ? FOR UPDATE",
          UUID.class,
          customer);
      List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
      for (Callable<MockHttpServletResponse> request : requests) {
        futures.add(pool.submit(request));
        awaitLockWaiters(holder, futures.size());
      }
      holder.commit();
      List<MockHttpServletResponse> responses = new ArrayList<>();
      for (Future<MockHttpServletResponse> future : futures) {
        responses.add(future.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
      }
      return responses;
    } finally {
      pool.shutdownNow();
    }
  }

  private void awaitLockWaiters(Connection holder, int count) throws SQLException {
    awaitBlockedBy(backendPid(holder), count);
  }
}
