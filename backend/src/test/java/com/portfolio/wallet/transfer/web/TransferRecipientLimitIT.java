package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * Recipient attempts of {@code POST /transfers} in the lookup windows (documentations/02 §1.8; doc
 * 06 MVP-ADR-20 amendment "Transfers count as lookups"; doc 08 §4C.2 Q5, §4C.4 criterion 14):
 *
 * <ul>
 *   <li>only a transfer whose recipient is <b>not found</b> counts in the 10-per-user and 30-per-IP
 *       windows shared with {@code GET /wallets/lookup}, with a {@code WALLET_LOOKUP} audit row;
 *   <li>a found recipient (another customer's wallet, or the caller's own) neither counts nor is
 *       audited as a lookup;
 *   <li>the limit applies to <b>every</b> transfer: over a window, {@code 429} with {@code
 *       Retry-After} whether the recipient exists or not, so the answer discloses nothing
 *       (MVP-ADR-20); the refused request is neither counted nor audited, and the key is not used.
 * </ul>
 */
class TransferRecipientLimitIT extends TransferFlowSupport {

  private MockHttpServletResponse lookup(Account caller, String number, String ip)
      throws Exception {
    return send(
        get("/api/v1/wallets/lookup")
            .param("walletNumber", number)
            .with(
                r -> {
                  r.setRemoteAddr(ip);
                  return r;
                }),
        caller.token());
  }

  private MockHttpServletResponse notFoundTransfer(Account sender, String ip) throws Exception {
    return postTransfer(
        transferBody(absentNumber(), "10.00", null), sender.token(), UUID.randomUUID(), ip);
  }

  private MockHttpServletResponse transferTo(Account sender, String number, UUID key, String ip)
      throws Exception {
    return postTransfer(transferBody(number, "10.00", null), sender.token(), key, ip);
  }

  /**
   * Effect (a): successful transfers are limited only by the money limit, not the lookup window.
   */
  @Test
  void moreThanTenSuccessfulTransfersAMinuteAreNotRefusedByTheLookupWindow() throws Exception {
    Account sender = funded("1000.00");
    Account recipient = withWallet();
    String ip = randomIp();

    for (int i = 0; i < 12; i++) {
      assertThat(transferTo(sender, numberOf(recipient), UUID.randomUUID(), ip).getStatus())
          .as("transfer " + (i + 1))
          .isEqualTo(201);
    }

    assertThat(lookupAttempts(sender)).isZero();
    assertThat(lookupAudits(sender)).isEmpty();
    assertBalances(sender, "880.00", "0.00");
    // The window is still empty: a lookup is allowed.
    assertThat(lookup(sender, numberOf(recipient), ip).getStatus()).isEqualTo(200);
  }

  /** Effects (b) and (c), per user. */
  @Test
  void notFoundTransfersCountAndThenEveryTransferIs429() throws Exception {
    Account sender = funded("100.00");
    Account recipient = withWallet();
    // A found and a self transfer first: neither counts (c).
    assertThat(transferTo(sender, numberOf(recipient), UUID.randomUUID(), randomIp()).getStatus())
        .isEqualTo(201);
    assertThat(transferTo(sender, numberOf(sender), UUID.randomUUID(), randomIp()).getStatus())
        .isEqualTo(409);
    assertThat(lookupAttempts(sender)).isZero();
    // Ten not-found transfers, each from its own IP: each counts and is audited.
    for (int i = 0; i < 10; i++) {
      assertThat(notFoundTransfer(sender, randomIp()).getStatus()).isEqualTo(404);
    }
    assertThat(lookupAttempts(sender)).isEqualTo(10);
    assertThat(lookupAudits(sender))
        .hasSize(10)
        .allSatisfy(
            audit -> {
              assertThat(audit).containsEntry("result", "FAILURE");
              assertThat((String) audit.get("new_value")).isEqualTo("{\"outcome\": \"NOT_FOUND\"}");
            });
    UUID key = UUID.randomUUID();

    // Over the window: 429 whether the recipient exists, is the caller's own, or is missing.
    List<MockHttpServletResponse> limited =
        List.of(
            transferTo(sender, numberOf(recipient), key, randomIp()),
            transferTo(sender, numberOf(sender), UUID.randomUUID(), randomIp()),
            notFoundTransfer(sender, randomIp()));
    for (MockHttpServletResponse response : limited) {
      assertThat(response.getStatus()).isEqualTo(429);
      assertThat(errorCode(response)).isEqualTo("RATE_LIMITED");
      assertThat(Long.parseLong(response.getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1L, 60L);
      assertThat(stable(response)).isEqualTo(stable(limited.get(0)));
    }
    // Neither counted nor audited; nothing stored or moved.
    assertThat(lookupAttempts(sender)).isEqualTo(10);
    assertThat(lookupAudits(sender)).hasSize(10);
    assertThat(transferRecords(sender, key)).isZero();
    assertBalances(sender, "90.00", "0.00");
    // The lookup shares the window.
    assertThat(lookup(sender, numberOf(recipient), randomIp()).getStatus()).isEqualTo(429);
  }

  /** Effect (d): the per-IP window likewise. */
  @Test
  void onlyNotFoundTransfersCountPerIpAndThenEveryTransferIs429() throws Exception {
    String ip = randomIp();
    Account recipient = withWallet();
    // Found transfers from the shared address do not count.
    Account payer = funded("100.00");
    for (int i = 0; i < 5; i++) {
      assertThat(transferTo(payer, numberOf(recipient), UUID.randomUUID(), ip).getStatus())
          .isEqualTo(201);
    }
    for (int user = 0; user < 3; user++) {
      Account sender = withWallet();
      for (int i = 0; i < 10; i++) {
        assertThat(notFoundTransfer(sender, ip).getStatus()).isEqualTo(404);
      }
    }
    Account fresh = funded("100.00");

    MockHttpServletResponse found = transferTo(fresh, numberOf(recipient), UUID.randomUUID(), ip);
    MockHttpServletResponse missing = notFoundTransfer(fresh, ip);

    for (MockHttpServletResponse response : List.of(found, missing)) {
      assertThat(response.getStatus()).isEqualTo(429);
      assertThat(response.getHeader(HttpHeaders.RETRY_AFTER)).isNotBlank();
    }
    assertThat(lookupAttempts(fresh)).isZero();
    assertThat(lookupAudits(fresh)).isEmpty();
    assertBalances(fresh, "100.00", "0.00");
    // From another address the same customer is not limited.
    assertThat(transferTo(fresh, numberOf(recipient), UUID.randomUUID(), randomIp()).getStatus())
        .isEqualTo(201);
    // KYC comes before the recipient step, so a caller without a wallet is still 403 here.
    MockHttpServletResponse noWallet =
        postTransfer(
            transferBody(absentNumber(), "10.00", null), customer().token(), UUID.randomUUID(), ip);
    assertThat(noWallet.getStatus()).isEqualTo(403);
    assertThat(errorCode(noWallet)).isEqualTo("KYC_REQUIRED");
  }
}
