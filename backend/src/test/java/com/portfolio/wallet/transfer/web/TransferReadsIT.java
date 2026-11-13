package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /transfers}, {@code GET /transfers/{id}} and {@code GET /transfers/{id}/status}
 * (documentations/02 §1.5, §2.4; doc 06 MVP-ADR-19, -20, -24 "Visibility (Q8)"; doc 08 §4C.2 Q8,
 * §4C.4 criteria 13, 20): the same rule on all three. The sender sees every status, the receiver
 * only {@code COMPLETED}, a third party nothing; everything else is the identical {@code 404}.
 */
class TransferReadsIT extends TransferReviewFlowSupport {

  private Account sender;
  private Account receiver;
  private Account stranger;
  private UUID heldId;
  private UUID blockedId;
  private UUID completedId;
  private UUID incomingId;

  @BeforeEach
  void transfersInEveryVisibleState() throws Exception {
    sender = funded("3000.00");
    receiver = funded("1000.00");
    stranger = withWallet();
    // Held first: once a transfer to the receiver completed, the same amount scores one less.
    heldId = held(sender, receiver);
    MockHttpServletResponse blocked = transfer(sender, receiver, BLOCK);
    assertThat(blocked.getStatus()).isEqualTo(403);
    blockedId =
        (UUID)
            transfersOf(sender).stream()
                .filter(row -> "BLOCKED".equals(row.get("status")))
                .findFirst()
                .orElseThrow()
                .get("id");
    completedId = transferId(transfer(sender, receiver, "100.00"));
    incomingId = transferId(transfer(receiver, sender, "40.00"));
  }

  private MockHttpServletResponse list(Account caller, String query) throws Exception {
    return getAs(TRANSFERS + query, caller.token());
  }

  private List<String> ids(MockHttpServletResponse response) throws Exception {
    List<String> ids = new ArrayList<>();
    body(response).get("items").forEach(item -> ids.add(item.get("id").asString()));
    return ids;
  }

  @Test
  void theSenderSeesEveryStatusNewestFirst() throws Exception {
    MockHttpServletResponse page = list(sender, "");

    assertThat(page.getStatus()).isEqualTo(200);
    assertThat(ids(page))
        .containsExactly(
            incomingId.toString(), completedId.toString(), blockedId.toString(), heldId.toString());
    JsonNode body = body(page);
    assertThat(body.get("page").get("totalElements").asLong()).isEqualTo(4);
    assertThat(body.get("page").get("totalPages").asLong()).isEqualTo(1);
    assertThat(body.get("page").get("size").asInt()).isEqualTo(20);
    JsonNode items = body.get("items");
    assertThat(items.get(0).get("direction").asString()).isEqualTo("RECEIVED");
    assertThat(items.get(1).get("direction").asString()).isEqualTo("SENT");
    assertThat(items.get(2).get("status").asString()).isEqualTo("BLOCKED");
    assertThat(items.get(3).get("status").asString()).isEqualTo("PENDING_REVIEW");
    assertThat(items.get(3).get("reviewExpiresAt").isNull()).isFalse();
    String number = numberOf(receiver);
    for (JsonNode item : items) {
      assertThat(item.get("counterparty").get("maskedName").asString()).isEqualTo("Somchai J.");
      assertThat(item.get("counterparty").get("maskedWalletNumber").asString())
          .isEqualTo("*******" + number.substring(7));
    }
    assertNoInternals(page);
  }

  @Test
  void theReceiverSeesOnlyCompletedTransfers() throws Exception {
    MockHttpServletResponse page = list(receiver, "");

    assertThat(ids(page)).containsExactly(incomingId.toString(), completedId.toString());
    assertThat(body(page).get("items").get(1).get("direction").asString()).isEqualTo("RECEIVED");
    assertThat(body(page).get("items").get(1).get("counterparty").get("maskedWalletNumber"))
        .isNotNull();
    assertThat(getAs(TRANSFERS + "/" + completedId, receiver.token()).getStatus()).isEqualTo(200);
    MockHttpServletResponse status =
        getAs(TRANSFERS + "/" + completedId + "/status", receiver.token());
    assertThat(status.getStatus()).isEqualTo(200);
    assertThat(body(status).propertyNames()).containsExactlyInAnyOrder("status", "ledgerStatus");
    assertThat(body(status).get("status").asString()).isEqualTo("COMPLETED");
    assertThat(body(status).get("ledgerStatus").asString()).isEqualTo("POSTED");
  }

  @Test
  void theSameRuleOnDetailAndStatusGivesTheIdentical404() throws Exception {
    MockHttpServletResponse missing = getAs(TRANSFERS + "/" + UUID.randomUUID(), sender.token());
    assertThat(missing.getStatus()).isEqualTo(404);
    String notFound = stable(missing);

    List<MockHttpServletResponse> hidden = new ArrayList<>();
    for (UUID id : List.of(heldId, blockedId)) {
      hidden.add(getAs(TRANSFERS + "/" + id, receiver.token()));
      hidden.add(getAs(TRANSFERS + "/" + id + "/status", receiver.token()));
    }
    for (UUID id : List.of(heldId, blockedId, completedId, incomingId)) {
      hidden.add(getAs(TRANSFERS + "/" + id, stranger.token()));
      hidden.add(getAs(TRANSFERS + "/" + id + "/status", stranger.token()));
    }
    hidden.add(getAs(TRANSFERS + "/" + UUID.randomUUID() + "/status", sender.token()));
    for (MockHttpServletResponse response : hidden) {
      assertThat(response.getStatus()).isEqualTo(404);
      assertThat(stable(response)).isEqualTo(notFound);
    }
    assertThat(ids(list(stranger, ""))).isEmpty();

    // The sender sees each of its own on both endpoints.
    for (UUID id : List.of(heldId, blockedId, completedId, incomingId)) {
      MockHttpServletResponse detail = getAs(TRANSFERS + "/" + id, sender.token());
      assertThat(detail.getStatus()).isEqualTo(200);
      assertNoInternals(detail);
      assertThat(getAs(TRANSFERS + "/" + id + "/status", sender.token()).getStatus())
          .isEqualTo(200);
    }
    MockHttpServletResponse heldStatus =
        getAs(TRANSFERS + "/" + heldId + "/status", sender.token());
    assertThat(body(heldStatus).get("status").asString()).isEqualTo("PENDING_REVIEW");
    assertThat(body(heldStatus).get("ledgerStatus").isNull()).isTrue();
  }

  @Test
  void pagesAreBoundedAndDoNotOverlap() throws Exception {
    MockHttpServletResponse first = list(sender, "?page=0&size=3");
    MockHttpServletResponse second = list(sender, "?page=1&size=3");

    assertThat(ids(first)).hasSize(3);
    assertThat(ids(second)).containsExactly(heldId.toString());
    assertThat(body(first).get("page").get("totalPages").asLong()).isEqualTo(2);
    assertThat(ids(list(sender, "?page=5&size=100"))).isEmpty();
    for (String query : List.of("?size=0", "?size=101", "?page=-1", "?size=x")) {
      MockHttpServletResponse refused = list(sender, query);
      assertThat(refused.getStatus()).as(query).isEqualTo(400);
      assertThat(errorCode(refused)).isEqualTo("VALIDATION_ERROR");
    }
  }

  /** Q8: a cancelled or expired transfer is the sender's only; the receiver gets the 404. */
  @Test
  void theReceiverNeverSeesACancelledOrExpiredTransfer() throws Exception {
    Account payer = funded("3000.00");
    Account payee = withWallet();
    UUID cancelled = held(payer, payee);
    assertThat(cancel(cancelled, payer).getStatus()).isEqualTo(200);
    UUID expired = held(payer, payee);
    overdue(expired, 60);
    assertThat(cancel(expired, payer).getStatus()).isEqualTo(409);
    assertThat(transferRow(expired)).containsEntry("status", "EXPIRED");

    assertThat(ids(list(payee, ""))).isEmpty();
    assertThat(body(list(payee, "")).get("page").get("totalElements").asLong()).isZero();
    String notFound = stable(getAs(TRANSFERS + "/" + UUID.randomUUID(), payee.token()));
    for (UUID id : List.of(cancelled, expired)) {
      for (String path : List.of(TRANSFERS + "/" + id, TRANSFERS + "/" + id + "/status")) {
        MockHttpServletResponse hidden = getAs(path, payee.token());
        assertThat(hidden.getStatus()).isEqualTo(404);
        assertThat(stable(hidden)).isEqualTo(notFound);
      }
    }
    // The sender sees both, with their final status.
    assertThat(ids(list(payer, ""))).containsExactly(expired.toString(), cancelled.toString());
    assertThat(
            body(getAs(TRANSFERS + "/" + expired + "/status", payer.token()))
                .get("status")
                .asString())
        .isEqualTo("EXPIRED");
    assertThat(body(getAs(TRANSFERS + "/" + cancelled, payer.token())).get("status").asString())
        .isEqualTo("CANCELLED");
  }

  /** Criterion 20: no description, score, rule, investigation or party ids. */
  private void assertNoInternals(MockHttpServletResponse response) throws Exception {
    assertThat(response.getContentAsString())
        .doesNotContain("rent")
        .doesNotContain("score")
        .doesNotContain("TRANSFER_AMOUNT")
        .doesNotContain("NEW_RECIPIENT")
        .doesNotContain("NEW_SENDER_WALLET")
        .doesNotContain("description")
        .doesNotContain(sender.id().toString())
        .doesNotContain(receiver.id().toString())
        .doesNotContain(walletId(sender).toString())
        .doesNotContain(walletId(receiver).toString())
        .doesNotContain(numberOf(receiver))
        .doesNotContain(numberOf(sender));
  }
}
