package com.portfolio.wallet.wallet.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.portfolio.wallet.wallet.application.WalletNumberGenerator;
import com.portfolio.wallet.wallet.persistence.MoneyMovementRepository.Table;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * No log line of the PR 4b paths carries a wallet number, a legal name, a deposit note, a KYC
 * document reference, a customer or wallet id, an {@code Idempotency-Key} or, for the lookup, the
 * client IP (doc 08 §4B.4 AC 11; X1–X6 "logs carry no customer id, wallet number or IP"; doc 06
 * MVP-ADR-20; {@code IdempotencyService}: the key stays out of the logs). Every outcome is driven,
 * including the refusals, the rate limit and an injected server error, because those are the paths
 * that log.
 */
@ExtendWith(OutputCaptureExtension.class)
class MoneyAndLookupLogsIT extends MoneyFlowSupport {

  private static final String LOOKUP = "/api/v1/wallets/lookup";

  private MockHttpServletResponse lookup(Account caller, String walletNumber, String ip)
      throws Exception {
    return send(
        get(LOOKUP)
            .param("walletNumber", walletNumber)
            .with(
                r -> {
                  r.setRemoteAddr(ip);
                  return r;
                }),
        caller.token());
  }

  private String numberOf(Account account) {
    return (String) walletOf(account.id()).get("wallet_number");
  }

  private static void assertNoneLogged(String logged, List<String> secrets) {
    assertThat(logged).as("captured output").isNotEmpty();
    for (String secret : secrets) {
      assertThat(logged).as("log must not contain a sensitive value").doesNotContain(secret);
    }
  }

  @Test
  void moneyPathsLogNoIdentityNumberNoteOrKey(CapturedOutput output) throws Exception {
    Account customer = withWallet();
    UUID wallet = walletId(customer);
    String number = numberOf(customer);
    Account noWallet = customer();
    List<UUID> keys = new ArrayList<>();
    int start = output.getAll().length();

    UUID depositKey = UUID.randomUUID();
    keys.add(depositKey);
    postMoney(
        DEPOSITS,
        "{\"amount\":1000,\"currency\":\"THB\",\"reference\":\"note-4f2a private\"}",
        customer.token(),
        depositKey);
    // Replay and key reuse.
    postMoney(
        DEPOSITS,
        "{\"amount\":1000,\"currency\":\"THB\",\"reference\":\"note-4f2a private\"}",
        customer.token(),
        depositKey);
    deposit(customer, "999", depositKey);
    // Request errors that do not consume the key.
    for (String body :
        List.of(
            "{\"amount\":100.001,\"currency\":\"THB\"}",
            "{\"amount\":0,\"currency\":\"THB\"}",
            "{\"amount\":100,\"currency\":\"USD\"}",
            "{\"amount\":100,\"currency\":\"THB\",\"fundingSource\":\"card-5500-0000\"}",
            "{\"amount\":100,\"currency\":\"THB\",\"reference\":\"\\u0007note-4f2a\"}")) {
      UUID key = UUID.randomUUID();
      keys.add(key);
      postMoney(DEPOSITS, body, customer.token(), key);
    }
    postMoney(DEPOSITS, "{\"amount\":100,\"currency\":\"THB\"}", customer.token(), null);
    // Stored refusals: limit, balance, below minimum.
    for (String amount : List.of("30000.01", "5000", "99.99")) {
      UUID key = UUID.randomUUID();
      keys.add(key);
      withdraw(customer, amount, key);
    }
    UUID noWalletKey = UUID.randomUUID();
    keys.add(noWalletKey);
    withdraw(noWallet, "100", noWalletKey);
    // An injected server error in the money transaction.
    doThrow(new IllegalStateException("injected failure"))
        .when(movementRowsSpy)
        .insert(eq(Table.WITHDRAWALS), any(), any());
    UUID failedKey = UUID.randomUUID();
    keys.add(failedKey);
    assertThat(withdraw(customer, "100", failedKey).getStatus()).isEqualTo(500);
    reset(movementRowsSpy);
    // Frozen.
    postAs(FREEZE, null, customer.token());
    UUID frozenKey = UUID.randomUUID();
    keys.add(frozenKey);
    withdraw(customer, "100", frozenKey);

    String logged = output.getAll().substring(start);
    List<String> secrets = new ArrayList<>();
    secrets.addAll(
        List.of(
            "note-4f2a",
            "card-5500",
            number,
            "Somchai",
            "Jaidee",
            "AB1234567",
            customer.id().toString(),
            noWallet.id().toString(),
            wallet.toString()));
    keys.forEach(key -> secrets.add(key.toString()));
    assertNoneLogged(logged, secrets);
  }

  @Test
  void lookupPathsLogNoIdentityNumberOrAddress(CapturedOutput output) throws Exception {
    Account caller = withWallet();
    Account recipient = withWallet();
    String found = numberOf(recipient);
    String absent = new WalletNumberGenerator().next().value();
    char last = found.charAt(10);
    String wrongDigit = found.substring(0, 10) + (last == '9' ? '0' : (char) (last + 1));
    Account noWallet = customer();
    Account frozen = withWallet();
    postAs(FREEZE, null, frozen.token());
    ThreadLocalRandom random = ThreadLocalRandom.current();
    String ip =
        "10." + random.nextInt(256) + "." + random.nextInt(256) + "." + random.nextInt(1, 255);
    int start = output.getAll().length();

    lookup(caller, found, ip);
    lookup(caller, absent, ip);
    lookup(caller, wrongDigit, ip);
    lookup(caller, found.substring(1), ip);
    lookup(noWallet, found, ip);
    lookup(frozen, found, ip);
    for (int i = 0; i < 7; i++) {
      lookup(caller, found, ip);
    }
    assertThat(lookup(caller, found, ip).getStatus()).isEqualTo(429);

    String logged = output.getAll().substring(start);
    assertNoneLogged(
        logged,
        List.of(
            found,
            absent,
            wrongDigit,
            found.substring(1),
            "Somchai",
            "Jaidee",
            "AB1234567",
            ip,
            caller.id().toString(),
            recipient.id().toString(),
            noWallet.id().toString(),
            frozen.id().toString(),
            walletId(recipient).toString()));
  }
}
