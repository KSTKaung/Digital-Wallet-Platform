package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * No log line and no customer response of the transfer paths carries the description, a risk score
 * or rule, a wallet number, a party's id or the {@code Idempotency-Key} (documentations/08 §4C.4
 * criterion 20; doc 06 MVP-ADR-20, -24 "Security implications"). Every outcome is driven, the
 * refusals and an injected risk failure included, because those are the paths that log.
 */
@ExtendWith(OutputCaptureExtension.class)
class TransferLogsIT extends TransferReviewFlowSupport {

  private static final String DESCRIPTION = "note-5b1d private";

  @Test
  void transferPathsLogNoDescriptionScoreRuleNumberOrKey(CapturedOutput output) throws Exception {
    Account sender = funded("5000.00");
    Account recipient = withWallet();
    Account other = withWallet();
    String number = numberOf(recipient);
    List<UUID> keys = new ArrayList<>();
    List<MockHttpServletResponse> responses = new ArrayList<>();
    int start = output.getAll().length();

    // Held, replayed, reused key, blocked, completed, refused, not found, self, cancelled.
    UUID heldKey = UUID.randomUUID();
    keys.add(heldKey);
    responses.add(send(heldKey, number, HOLD, sender));
    responses.add(send(heldKey, number, HOLD, sender));
    responses.add(send(heldKey, number, ALLOW, sender));
    UUID blockedKey = UUID.randomUUID();
    keys.add(blockedKey);
    responses.add(send(blockedKey, numberOf(other), BLOCK, sender));
    UUID completedKey = UUID.randomUUID();
    keys.add(completedKey);
    responses.add(send(completedKey, number, "10.00", sender));
    UUID refusedKey = UUID.randomUUID();
    keys.add(refusedKey);
    responses.add(send(refusedKey, number, "4999.00", sender));
    responses.add(send(UUID.randomUUID(), absentNumber(), "10.00", sender));
    responses.add(send(UUID.randomUUID(), numberOf(sender), "10.00", sender));
    UUID held = transferId(responses.get(0));
    responses.add(cancel(held, sender));
    responses.add(cancel(held, sender));
    responses.add(getAs(TRANSFERS, sender.token()));
    responses.add(getAs(TRANSFERS + "/" + held, sender.token()));
    // An injected risk failure, which logs at ERROR.
    UUID failedKey = UUID.randomUUID();
    keys.add(failedKey);
    doThrow(new DataIntegrityViolationException("injected")).when(riskRowsSpy).lockSender(any());
    responses.add(send(failedKey, number, "10.00", sender));

    String logged = output.getAll().substring(start);
    assertThat(logged).as("captured output").isNotEmpty();
    List<String> secrets = new ArrayList<>();
    secrets.add(DESCRIPTION);
    secrets.add("note-5b1d");
    secrets.add(number);
    secrets.add(numberOf(sender));
    secrets.add(numberOf(other));
    secrets.add(sender.id().toString());
    secrets.add(recipient.id().toString());
    secrets.add(walletId(sender).toString());
    secrets.add(walletId(recipient).toString());
    secrets.add("TRANSFER_AMOUNT");
    secrets.add("NEW_RECIPIENT");
    secrets.add("NEW_SENDER_WALLET");
    secrets.add("TRANSFER_FREQUENCY");
    keys.forEach(key -> secrets.add(key.toString()));
    for (String secret : secrets) {
      assertThat(logged).as("log must not contain a sensitive value").doesNotContain(secret);
    }
    assertThat(logged).doesNotContainIgnoringCase("\"score\"");

    for (MockHttpServletResponse response : responses) {
      String content = response.getContentAsString();
      assertThat(content)
          .doesNotContain("note-5b1d")
          .doesNotContain("score")
          .doesNotContain("TRANSFER_AMOUNT")
          .doesNotContain("NEW_RECIPIENT")
          .doesNotContain("NEW_SENDER_WALLET")
          .doesNotContain("TRANSFER_FREQUENCY")
          .doesNotContain("description")
          .doesNotContain(number);
    }
  }

  private MockHttpServletResponse send(UUID key, String number, String amount, Account sender)
      throws Exception {
    return postTransfer(transferBody(number, amount, DESCRIPTION), sender.token(), key, randomIp());
  }
}
