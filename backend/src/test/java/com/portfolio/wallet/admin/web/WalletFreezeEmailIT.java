package com.portfolio.wallet.admin.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.EmailMessage;
import com.portfolio.wallet.wallet.application.WalletNumberGenerator;
import com.portfolio.wallet.wallet.application.WalletStatusService;
import com.portfolio.wallet.wallet.application.WalletStatusView;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The customer's email on a staff freeze and unfreeze (documentations/06 MVP-ADR-10 amendment,
 * freeze emails; MVP-ADR-25 outbox): an outbox row is written in the change's transaction, only
 * when the status really changed, and sent after the commit; nothing on a rollback, a repeated
 * request or the customer's own freeze; exactly one of concurrent requests writes a row; a failed
 * lookup or send leaves the change committed, is logged without the address and is retried when
 * due.
 */
@ExtendWith(OutputCaptureExtension.class)
class WalletFreezeEmailIT extends AdminFlowSupport {

  private static final String CUSTOMERS = "/api/v1/admin/customers/";
  private static final long TIMEOUT_SECONDS = 30;
  private static final int CONCURRENT = 5;

  @Autowired WalletStatusService statuses;
  @Autowired PlatformTransactionManager transactions;

  /** A customer with an approved application, and so an ACTIVE wallet; returns the wallet id. */
  private UUID walletFor(Account customer) throws Exception {
    assertThat(approve(pending(customer), officer().token()).getStatus()).isEqualTo(200);
    awaitMail(customer.email(), EmailKind.KYC_APPROVED);
    return (UUID) walletsOf(customer.id()).getFirst().get("id");
  }

  private MockHttpServletResponse change(String action, UUID customer, String reason, String token)
      throws Exception {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("reason", reason);
    return postAs(CUSTOMERS + customer + "/" + action, body, token);
  }

  private String walletStatus(UUID walletId) {
    return jdbc.queryForObject(
        "SELECT status FROM wallet.wallets WHERE id = ?", String.class, walletId);
  }

  private void assertStatuses(UUID walletId, String status) {
    assertThat(walletStatus(walletId)).isEqualTo(status);
    assertThat(ledgerAccountsOf(walletId).getFirst().get("status"))
        .as("ledger account follows the wallet")
        .isEqualTo(status);
  }

  private int statusAudits(UUID walletId, String action) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit.audit_logs WHERE resource = 'WALLET' AND resource_id = ?"
            + " AND action = ?",
        Integer.class,
        walletId.toString(),
        action);
  }

  private static void assertNoAccountDetails(
      EmailMessage message, Map<String, Object> wallet, String... more) {
    String text = message.subject() + " " + message.body();
    assertThat(text)
        .doesNotContain(
            (String) wallet.get("wallet_number"),
            wallet.get("id").toString(),
            wallet.get("available_balance").toString(),
            "balance",
            FIRST_NAME,
            LAST_NAME,
            REFERENCE)
        .doesNotContain(more);
  }

  // Sending -------------------------------------------------------------------------------------

  @Test
  void aStaffFreezeEmailsTheCustomerOnceWithoutTheReasonOrAccountDetails() throws Exception {
    Account customer = customer();
    UUID walletId = walletFor(customer);
    Map<String, Object> wallet = walletsOf(customer.id()).getFirst();

    MockHttpServletResponse response =
        change("freeze", customer.id(), "Reported stolen phone 4471", admin());

    assertThat(response.getStatus()).isEqualTo(200);
    EmailMessage message = awaitMail(customer.email(), EmailKind.WALLET_FROZEN);
    assertThat(message.subject()).isEqualTo("Your wallet has been frozen");
    assertThat(message.body()).contains("frozen", "contact support");
    assertNoAccountDetails(message, wallet, "Reported stolen phone 4471", "4471");
    assertMailCountStays(customer.email(), EmailKind.WALLET_FROZEN, 1);
    assertStatuses(walletId, "FROZEN");
  }

  @Test
  void aFreezeThenAnUnfreezeSendsTwoEmails() throws Exception {
    Account customer = customer();
    walletFor(customer);
    Map<String, Object> wallet = walletsOf(customer.id()).getFirst();
    String admin = admin();

    assertThat(change("freeze", customer.id(), "check", admin).getStatus()).isEqualTo(200);
    awaitMail(customer.email(), EmailKind.WALLET_FROZEN);
    assertThat(change("unfreeze", customer.id(), "cleared 5582", admin).getStatus()).isEqualTo(200);

    EmailMessage unfrozen = awaitMail(customer.email(), EmailKind.WALLET_UNFROZEN);
    assertThat(unfrozen.subject()).isEqualTo("Your wallet has been unfrozen");
    assertThat(unfrozen.body()).contains("unfrozen", "contact support");
    assertNoAccountDetails(unfrozen, wallet, "cleared 5582");
    assertMailCountStays(customer.email(), EmailKind.WALLET_FROZEN, 1);
    assertMailCountStays(customer.email(), EmailKind.WALLET_UNFROZEN, 1);
    // The KYC approval notice and the two status notices, nothing else.
    assertThat(mail.messages().stream().filter(m -> m.to().equals(customer.email())))
        .extracting(EmailMessage::kind)
        .containsExactly(
            EmailKind.KYC_APPROVED, EmailKind.WALLET_FROZEN, EmailKind.WALLET_UNFROZEN);
  }

  // Nothing sent --------------------------------------------------------------------------------

  @Test
  void aRepeatedFreezeOrUnfreezeSendsNothing() throws Exception {
    Account customer = customer();
    walletFor(customer);
    String admin = admin();

    assertThat(change("unfreeze", customer.id(), "no-op", admin).getStatus()).isEqualTo(200);
    assertThat(change("freeze", customer.id(), "first", admin).getStatus()).isEqualTo(200);
    assertThat(change("freeze", customer.id(), "second", admin).getStatus()).isEqualTo(200);
    assertThat(change("freeze", customer.id(), "third", admin).getStatus()).isEqualTo(200);

    assertMailCountStays(customer.email(), EmailKind.WALLET_FROZEN, 1);
    assertMailCountStays(customer.email(), EmailKind.WALLET_UNFROZEN, 0);
  }

  @Test
  void theCustomersOwnFreezeSendsNothing() throws Exception {
    Account customer = customer();
    UUID walletId = walletFor(customer);

    assertThat(postAs("/api/v1/wallet/freeze", null, customer.token()).getStatus()).isEqualTo(200);

    assertStatuses(walletId, "FROZEN");
    assertMailCountStays(customer.email(), EmailKind.WALLET_FROZEN, 0);
    // A staff freeze of the wallet the customer froze changes nothing, so it sends nothing either.
    assertThat(change("freeze", customer.id(), "check", admin()).getStatus()).isEqualTo(200);
    assertMailCountStays(customer.email(), EmailKind.WALLET_FROZEN, 0);
  }

  /**
   * A failure after the wallet's status was written: the ledger account's update runs, then the
   * step fails. Everything rolls back and the email, not yet registered, is never queued.
   */
  @Test
  void aFailureAfterTheStatusChangeRollsBackAndSendsNothing() throws Exception {
    Account customer = customer();
    UUID walletId = walletFor(customer);
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              throw new IllegalStateException("forced ledger failure");
            })
        .when(spied(ledgerAccounts))
        .freezeCustomerAccount(any());

    MockHttpServletResponse response = change("freeze", customer.id(), "check", admin());

    assertThat(response.getStatus()).isEqualTo(500);
    assertThat(errorCode(response)).isEqualTo("SYSTEM_ERROR");
    assertStatuses(walletId, "ACTIVE");
    assertThat(statusAudits(walletId, "WALLET_FREEZE")).isZero();
    assertThat(outboxRows(customer.id()))
        .extracting(r -> r.get("kind"))
        .doesNotContain("WALLET_FROZEN");
    assertMailCountStays(customer.email(), EmailKind.WALLET_FROZEN, 0);
  }

  /**
   * The email is registered inside the transaction: if the transaction rolls back after the change
   * completed, here because an enclosing transaction is marked rollback-only, nothing is sent.
   */
  @Test
  void aRollbackAfterTheEmailWasRegisteredSendsNothing() throws Exception {
    Account customer = customer();
    UUID walletId = walletFor(customer);
    UUID admin = account(Role.ADMIN).id();

    WalletStatusView view =
        new TransactionTemplate(transactions)
            .execute(
                status -> {
                  WalletStatusView changed =
                      statuses.freezeByStaff(admin, customer.id(), "check", null);
                  status.setRollbackOnly();
                  return changed;
                });

    assertThat(view.status()).as("the change itself completed").isEqualTo("FROZEN");
    assertStatuses(walletId, "ACTIVE");
    assertThat(statusAudits(walletId, "WALLET_FREEZE")).isZero();
    assertThat(outboxRows(customer.id()))
        .extracting(r -> r.get("kind"))
        .doesNotContain("WALLET_FROZEN");
    assertMailCountStays(customer.email(), EmailKind.WALLET_FROZEN, 0);
  }

  @Test
  void withoutAnAccountToEmailTheChangeStandsAndTheOutboxRowWaitsForItsRetry(CapturedOutput output)
      throws Exception {
    // A wallet whose customer has no auth account: the address lookup finds nothing.
    Map<String, Object> wallet =
        with(validWallet(), "wallet_number", new WalletNumberGenerator().next().value());
    insert("wallet.wallets", wallet);
    UUID walletId = (UUID) wallet.get("id");
    UUID customerId = (UUID) wallet.get("customer_id");
    insert(
        "ledger.ledger_accounts",
        row(
            "id",
            UUID.randomUUID(),
            "account_type",
            "CUSTOMER",
            "owner_reference",
            walletId,
            "currency",
            THB));
    long frozenBefore = mailCount(EmailKind.WALLET_FROZEN);
    int logStart = output.getOut().length();

    MockHttpServletResponse response = change("freeze", customerId, "check 6620", admin());

    assertThat(response.getStatus()).isEqualTo(200);
    assertStatuses(walletId, "FROZEN");
    assertThat(statusAudits(walletId, "WALLET_FREEZE")).isOne();
    assertNoNewMailOfKind(EmailKind.WALLET_FROZEN, frozenBefore);
    // The failed lookup counts as an attempt and is retried later (MVP-ADR-25).
    Map<String, Object> row = outboxRows(customerId).getFirst();
    assertThat(row)
        .containsEntry("kind", "WALLET_FROZEN")
        .containsEntry("status", "PENDING")
        .containsEntry("attempts", 1)
        .containsEntry("last_failure", "NoAddress");
    String log = output.getOut().substring(logStart);
    assertThat(log)
        .contains("Email outbox: delivery failed, will retry")
        .contains(row.get("event_id").toString())
        .doesNotContain(
            customerId.toString(),
            walletId.toString(),
            (String) wallet.get("wallet_number"),
            "check 6620");
  }

  // Send failure ----------------------------------------------------------------------------------

  /**
   * MVP-ADR-25: a failed send leaves the change committed, counts one attempt with the exception's
   * class only, and is retried when due; the retry delivers the email once. The schedule itself is
   * tested with a fixed clock in {@code NotificationOutboxIT}; here the row is made due by moving
   * its next attempt time back.
   */
  @Test
  void aFailedSendLeavesTheChangeCommittedAndIsRetriedOnceDue(CapturedOutput output)
      throws Exception {
    Account customer = customer();
    UUID walletId = walletFor(customer);
    int logStart = output.getOut().length();
    mail.setFailing(true);
    try {
      MockHttpServletResponse response = change("freeze", customer.id(), "check 3391", admin());

      assertThat(response.getStatus()).isEqualTo(200);
      assertThat(response.getContentAsString()).isEqualTo("{\"status\":\"FROZEN\"}");
      deliverOutbox();
    } finally {
      mail.setFailing(false);
    }

    assertStatuses(walletId, "FROZEN");
    assertThat(statusAudits(walletId, "WALLET_FREEZE")).isOne();
    Map<String, Object> failed = frozenRow(customer);
    assertThat(failed)
        .containsEntry("status", "PENDING")
        .containsEntry("attempts", 1)
        .containsEntry("last_failure", "IllegalStateException");
    // Not due yet: nothing is sent even though the sender works again.
    assertMailCountStays(customer.email(), EmailKind.WALLET_FROZEN, 0);

    jdbc.update(
        "UPDATE notification.notifications SET next_attempt_at = now() - interval '1 second'"
            + " WHERE event_id = ?",
        failed.get("event_id"));
    awaitMail(customer.email(), EmailKind.WALLET_FROZEN);
    assertMailCountStays(customer.email(), EmailKind.WALLET_FROZEN, 1);
    assertThat(frozenRow(customer)).containsEntry("status", "SENT").containsEntry("attempts", 2);
    String log = output.getOut().substring(logStart);
    assertThat(log).contains("delivery failed, will retry", "IllegalStateException");
    assertThat(log).doesNotContain(customer.email(), "check 3391", "smtp down");
  }

  private Map<String, Object> frozenRow(Account customer) {
    return outboxRows(customer.id()).stream()
        .filter(r -> "WALLET_FROZEN".equals(r.get("kind")))
        .findFirst()
        .orElseThrow();
  }

  // Concurrency -----------------------------------------------------------------------------------

  /**
   * Of {@value #CONCURRENT} concurrent freezes of one ACTIVE wallet, exactly one changes it and one
   * outbox row is written, so one email is sent when nothing fails; the same for unfreezes. A test
   * transaction holds the wallet row until every request waits on it, so they really overlap.
   */
  @Test
  void ofConcurrentFreezesOrUnfreezesExactlyOneChangesTheStatusAndSendsAnEmail() throws Exception {
    Account customer = customer();
    UUID walletId = walletFor(customer);
    List<String> admins = new ArrayList<>();
    for (int i = 0; i < CONCURRENT; i++) {
      admins.add(admin());
    }

    List<MockHttpServletResponse> freezes = concurrently(walletId, "freeze", customer, admins);

    assertThat(freezes).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(200));
    assertThat(freezes)
        .allSatisfy(r -> assertThat(r.getContentAsString()).isEqualTo("{\"status\":\"FROZEN\"}"));
    assertStatuses(walletId, "FROZEN");
    assertThat(statusAudits(walletId, "WALLET_FREEZE")).isOne();
    assertMailCountStays(customer.email(), EmailKind.WALLET_FROZEN, 1);

    List<MockHttpServletResponse> unfreezes = concurrently(walletId, "unfreeze", customer, admins);

    assertThat(unfreezes).allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(200));
    assertStatuses(walletId, "ACTIVE");
    assertThat(statusAudits(walletId, "WALLET_UNFREEZE")).isOne();
    assertMailCountStays(customer.email(), EmailKind.WALLET_UNFROZEN, 1);
    assertMailCountStays(customer.email(), EmailKind.WALLET_FROZEN, 1);
  }

  private List<MockHttpServletResponse> concurrently(
      UUID walletId, String action, Account customer, List<String> tokens) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(tokens.size());
    try (Connection holder = openTransaction()) {
      queryOne(
          holder, "SELECT id FROM wallet.wallets WHERE id = ? FOR UPDATE", UUID.class, walletId);
      List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
      for (String token : tokens) {
        futures.add(pool.submit(() -> change(action, customer.id(), "concurrent", token)));
      }
      awaitLockWaiters(holder, tokens.size());
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

  private String admin() throws Exception {
    return account(Role.ADMIN).token();
  }
}
