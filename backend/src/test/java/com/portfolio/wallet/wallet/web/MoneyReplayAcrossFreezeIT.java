package com.portfolio.wallet.wallet.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.auth.domain.Role;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Idempotency across a freeze and a staff unfreeze (doc 02 §1.4 "One key gets one answer; to try
 * again after an unfreeze or a top-up, the client sends a new key"; doc 02 §2.3 order of checks:
 * the replay, step 5, comes before the frozen check, step 6; doc 08 §4B.4 AC 8, 9).
 */
class MoneyReplayAcrossFreezeIT extends MoneyFlowSupport {

  private void unfreeze(Account customer) throws Exception {
    String admin = account(Role.ADMIN).token();
    assertThat(
            postAs(
                    "/api/v1/admin/customers/" + customer.id() + "/unfreeze",
                    Map.of("reason", "Customer confirmed by phone"),
                    admin)
                .getStatus())
        .isEqualTo(200);
  }

  private int failureAudits(UUID customerId) {
    return jdbc.queryForObject(
        "SELECT count(*) FROM audit.audit_logs"
            + " WHERE actor_id = ? AND action IN ('DEPOSIT', 'WITHDRAWAL') AND result = 'FAILURE'",
        Integer.class,
        customerId);
  }

  @Test
  void oneKeyKeepsItsOneAnswerThroughAFreezeAndAnUnfreeze() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    funded(customer, "1000.00");
    UUID before = UUID.randomUUID();
    MockHttpServletResponse completed = withdraw(customer, "200.00", before);
    assertThat(completed.getStatus()).isEqualTo(201);

    // Frozen: a success from before the freeze is still replayed, and moves nothing.
    assertThat(postAs(FREEZE, null, customer.token()).getStatus()).isEqualTo(200);
    MockHttpServletResponse replayWhileFrozen = withdraw(customer, "200", before);
    assertThat(replayWhileFrozen.getStatus()).isEqualTo(201);
    assertThat(body(replayWhileFrozen)).isEqualTo(body(completed));
    // A different body with that key is still key reuse, not WALLET_FROZEN.
    assertThat(errorCode(withdraw(customer, "250", before))).isEqualTo("IDEMPOTENCY_KEY_REUSED");

    UUID during = UUID.randomUUID();
    MockHttpServletResponse refused = withdraw(customer, "300.00", during);
    assertThat(refused.getStatus()).isEqualTo(409);
    assertThat(errorCode(refused)).isEqualTo("WALLET_FROZEN");
    UUID depositDuring = UUID.randomUUID();
    assertThat(errorCode(deposit(customer, "400.00", depositDuring))).isEqualTo("WALLET_FROZEN");
    assertThat(available(wallet)).isEqualByComparingTo("800.00");
    assertThat(failureAudits(customer.id())).isEqualTo(2);

    // Unfrozen by staff: the stored refusals are replayed, not re-decided, and not audited again.
    unfreeze(customer);
    MockHttpServletResponse replayedRefusal = withdraw(customer, "300", during);
    assertThat(replayedRefusal.getStatus()).isEqualTo(409);
    assertThat(stable(replayedRefusal)).isEqualTo(stable(refused));
    assertThat(errorCode(deposit(customer, "400.00", depositDuring))).isEqualTo("WALLET_FROZEN");
    assertThat(errorCode(deposit(customer, "401.00", depositDuring)))
        .isEqualTo("IDEMPOTENCY_KEY_REUSED");
    assertThat(failureAudits(customer.id())).isEqualTo(2);
    assertThat(body(withdraw(customer, "200.00", before))).isEqualTo(body(completed));
    assertThat(available(wallet)).isEqualByComparingTo("800.00");

    // A new key runs fresh.
    assertThat(withdraw(customer, "300.00", UUID.randomUUID()).getStatus()).isEqualTo(201);
    assertThat(deposit(customer, "400.00", UUID.randomUUID()).getStatus()).isEqualTo(201);
    assertThat(available(wallet)).isEqualByComparingTo("900.00");
    assertThat(ledgerTransactions(wallet)).isEqualTo(4);
    assertReconciled(wallet);
  }

  /** A withdrawal the freeze refused is not retried by the unfreeze: the day's usage is intact. */
  @Test
  void aRefusedMovementUsesNoneOfTheDailyLimit() throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    funded(customer, "1000.00");
    assertThat(postAs(FREEZE, null, customer.token()).getStatus()).isEqualTo(200);
    withdraw(customer, "500.00", UUID.randomUUID());
    deposit(customer, "500.00", UUID.randomUUID());
    unfreeze(customer);

    assertThat(usedToday(wallet, "WITHDRAWAL")).isEqualByComparingTo("0");
    assertThat(usedToday(wallet, "DEPOSIT")).isEqualByComparingTo("1000.00");
  }
}
