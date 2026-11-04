package com.portfolio.wallet.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.portfolio.wallet.auth.domain.Role;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Concurrent KYC decisions (documentations/08 §4B.4 AC 3; doc 07 §8). A test transaction holds the
 * row the decisions need first, both requests are seen waiting on it, and then it is released, so
 * the requests really overlap instead of running one after the other by chance.
 */
class KycApprovalConcurrencyIT extends AdminFlowSupport {

  private static final long TIMEOUT_SECONDS = 30;

  @Test
  void twoConcurrentApprovalsCreateExactlyOneWalletAndTheSecondIsAConflict() throws Exception {
    Account customer = customer();
    UUID application = pending(customer);
    String first = officer().token();
    String second = officer().token();

    List<MockHttpServletResponse> responses =
        whileHolding(
            "SELECT id FROM usr.kyc_applications WHERE id = ? FOR UPDATE",
            application,
            () -> approve(application, first),
            () -> approve(application, second));

    assertThat(responses)
        .extracting(MockHttpServletResponse::getStatus)
        .containsExactlyInAnyOrder(200, 409);
    MockHttpServletResponse loser =
        responses.stream().filter(r -> r.getStatus() == 409).findFirst().orElseThrow();
    assertThat(errorCode(loser)).isEqualTo("INVALID_KYC_STATE");
    List<Map<String, Object>> wallets = walletsOf(customer.id());
    assertThat(wallets).hasSize(1);
    assertThat(ledgerAccountsOf((UUID) wallets.getFirst().get("id"))).hasSize(1);
    assertThat(
            auditRows(application.toString()).stream()
                .filter(r -> "KYC_APPLICATION_APPROVE".equals(r.get("action"))))
        .hasSize(1);
  }

  @Test
  void aConcurrentApprovalAndRejectionDecideOnceAndConsistently() throws Exception {
    Account customer = customer();
    UUID application = pending(customer);
    String approver = officer().token();
    String rejecter = officer().token();

    List<MockHttpServletResponse> responses =
        whileHolding(
            "SELECT id FROM usr.kyc_applications WHERE id = ? FOR UPDATE",
            application,
            () -> approve(application, approver),
            () -> reject(application, "Unreadable", rejecter));

    assertThat(responses)
        .extracting(MockHttpServletResponse::getStatus)
        .containsExactlyInAnyOrder(200, 409);
    String status = (String) application(application).get("status");
    if ("APPROVED".equals(status)) {
      assertThat(walletsOf(customer.id())).hasSize(1);
      assertThat(profile(customer.id())).containsEntry("first_name", FIRST_NAME);
    } else {
      assertThat(status).isEqualTo("REJECTED");
      assertThat(walletsOf(customer.id())).isEmpty();
      assertThat(profile(customer.id())).containsEntry("first_name", null);
    }
  }

  /**
   * Step 2's profile lock relies on the approval copying the legal name under the profile row's
   * lock (V8): a profile update queued behind an approval re-reads the row and is refused.
   */
  @Test
  void aProfileUpdateQueuedBehindAnApprovalIsRefused() throws Exception {
    Account customer = customer();
    UUID application = pending(customer);
    String officer = officer().token();

    List<MockHttpServletResponse> responses =
        whileHolding(
            "SELECT user_id FROM usr.customers WHERE user_id = ? FOR UPDATE",
            customer.id(),
            () -> approve(application, officer),
            () ->
                putAs(
                    PROFILE,
                    Map.of("dateOfBirth", "1985-01-01", "countryCode", "JP"),
                    customer.token()));

    assertThat(responses.get(0).getStatus()).isEqualTo(200);
    assertThat(responses.get(1).getStatus()).isEqualTo(409);
    assertThat(errorCode(responses.get(1))).isEqualTo("INVALID_KYC_STATE");
    assertThat(
            jdbc.queryForObject(
                "SELECT country_code FROM usr.customers WHERE user_id = ?",
                String.class,
                customer.id()))
        .isEqualTo("TH");
  }

  /**
   * Holds {@code lockSql} in a test transaction, starts the second request only once the first
   * waits on a lock, releases the lock once both wait, and returns the responses in order.
   */
  private List<MockHttpServletResponse> whileHolding(
      String lockSql,
      UUID id,
      Callable<MockHttpServletResponse> first,
      Callable<MockHttpServletResponse> second)
      throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try (Connection holder = openTransaction()) {
      queryOne(holder, lockSql, UUID.class, id);
      Future<MockHttpServletResponse> a = pool.submit(first);
      awaitLockWaiters(holder, 1);
      Future<MockHttpServletResponse> b = pool.submit(second);
      awaitLockWaiters(holder, 2);
      holder.commit();
      return List.of(
          a.get(TIMEOUT_SECONDS, TimeUnit.SECONDS), b.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    } finally {
      pool.shutdownNow();
    }
  }

  private void awaitLockWaiters(Connection holder, int count) throws SQLException {
    awaitBlockedBy(backendPid(holder), count);
  }

  /**
   * A freeze while an approval is in flight (MVP-ADR-10 amendment; doc 08 §4B.2 Q9). The approval
   * is held open after it inserted the wallet and its ledger account. Uncommitted, the wallet does
   * not exist for anyone else: the customer's and the staff freeze get their 404 at once instead of
   * waiting or freezing half of it. After the commit, both statuses are {@code ACTIVE}, and a
   * customer freeze racing a staff freeze leaves both {@code FROZEN} with exactly one change.
   */
  @Test
  void aFreezeDuringAnApprovalFindsNoWalletAndAfterwardsBothStatusesStayInStep() throws Exception {
    Account customer = customer();
    UUID application = pending(customer);
    String officer = officer().token();
    String admin = account(Role.ADMIN).token();
    CountDownLatch opened = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              Object id = invocation.callRealMethod();
              opened.countDown();
              assertThat(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();
              return id;
            })
        .when(spied(ledgerAccounts))
        .openCustomerAccount(any());
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<MockHttpServletResponse> approval = pool.submit(() -> approve(application, officer));
      assertThat(opened.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)).isTrue();

      MockHttpServletResponse own = postAs("/api/v1/wallet/freeze", null, customer.token());
      MockHttpServletResponse staff =
          postAs(
              "/api/v1/admin/customers/" + customer.id() + "/freeze",
              Map.of("reason", "check"),
              admin);
      assertThat(approval.isDone()).as("the approval is still open").isFalse();
      release.countDown();

      assertThat(own.getStatus()).isEqualTo(404);
      assertThat(errorCode(own)).isEqualTo("WALLET_NOT_FOUND");
      assertThat(staff.getStatus()).isEqualTo(404);
      assertThat(errorCode(staff)).isEqualTo("NOT_FOUND");
      assertThat(approval.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      UUID walletId = (UUID) walletsOf(customer.id()).getFirst().get("id");
      assertStatuses(walletId, "ACTIVE");

      List<Future<MockHttpServletResponse>> freezes =
          List.of(
              pool.submit(() -> postAs("/api/v1/wallet/freeze", null, customer.token())),
              pool.submit(
                  () ->
                      postAs(
                          "/api/v1/admin/customers/" + customer.id() + "/freeze",
                          Map.of("reason", "check"),
                          admin)));
      for (Future<MockHttpServletResponse> freeze : freezes) {
        assertThat(freeze.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
      }
      assertStatuses(walletId, "FROZEN");
      assertThat(
              auditRows(walletId.toString()).stream()
                  .filter(r -> "WALLET_FREEZE".equals(r.get("action"))))
          .hasSize(1);
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  private void assertStatuses(UUID walletId, String status) {
    assertThat(walletsOf(customerOfWallet(walletId)).getFirst().get("status")).isEqualTo(status);
    assertThat(ledgerAccountsOf(walletId)).hasSize(1);
    assertThat(ledgerAccountsOf(walletId).getFirst().get("status")).isEqualTo(status);
  }
}
