package com.portfolio.wallet.wallet.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.wallet.application.MoneyMovementView;
import com.portfolio.wallet.wallet.application.WalletLookupView;
import com.portfolio.wallet.wallet.web.WalletLookupController.WalletLookupResponse;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The PR 4b request, response and view types never print a wallet number, a name, a note, an amount
 * or a card in {@code toString()}, so an accidental log of one of them leaks nothing (doc 08 §4B.4
 * AC 11; doc 06 MVP-ADR-20: the wallet number is the lookup key).
 */
class MoneyAndLookupToStringTest {

  private static final String NUMBER = "12345678903";

  @Test
  void movementViewAndResponseLeaveTheWalletNumberOut() {
    MoneyMovementView view =
        new MoneyMovementView(
            UUID.randomUUID(), NUMBER, new BigDecimal("4321.00"), "COMPLETED", "DEP-ABC", "POSTED");

    assertThat(view.toString()).doesNotContain(NUMBER).contains("COMPLETED");
    assertThat(MoneyMovementResponse.from(view).toString())
        .doesNotContain(NUMBER)
        .contains("COMPLETED");
  }

  @Test
  void requestsPrintNothingTheClientSent() {
    assertThat(
            new DepositRequest(
                    new BigDecimal("4321.00"), "THB", "private-note-77", "4111111111111111")
                .toString())
        .doesNotContain("4321")
        .doesNotContain("private-note-77")
        .doesNotContain("4111");
    assertThat(new WithdrawalRequest(new BigDecimal("4321.00"), "THB").toString())
        .doesNotContain("4321");
  }

  @Test
  void lookupViewAndResponsePrintNeitherNameNorNumber() {
    assertThat(new WalletLookupView("Somchai J.", "*******8903").toString())
        .doesNotContain("Somchai")
        .doesNotContain("8903");
    assertThat(new WalletLookupResponse("Somchai J.", "*******8903").toString())
        .doesNotContain("Somchai")
        .doesNotContain("8903");
  }
}
