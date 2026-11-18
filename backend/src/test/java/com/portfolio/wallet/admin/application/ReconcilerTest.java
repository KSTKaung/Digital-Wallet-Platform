package com.portfolio.wallet.admin.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.admin.application.ReconciliationMismatch.Kind;
import com.portfolio.wallet.ledger.application.LedgerReconciliationReads.AccountTotals;
import com.portfolio.wallet.ledger.application.LedgerReconciliationReads.Posting;
import com.portfolio.wallet.risk.application.RiskReconciliationReads.ReviewOutcome;
import com.portfolio.wallet.transfer.application.TransferReconciliationReads.CompletedTransfer;
import com.portfolio.wallet.transfer.application.TransferReconciliationReads.HeldSum;
import com.portfolio.wallet.transfer.application.TransferReconciliationReads.HeldTransferOutcome;
import com.portfolio.wallet.wallet.application.WalletReconciliationReads.CompletedMovement;
import com.portfolio.wallet.wallet.application.WalletReconciliationReads.ReservedUsage;
import com.portfolio.wallet.wallet.application.WalletReconciliationReads.WalletBalances;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The comparisons of the reconciliation (documentations/06 MVP-ADR-03 checks 1–5 and Σ DEBIT = Σ
 * CREDIT; MVP-ADR-22 Y5; MVP-ADR-24 "Review outcomes" D2, Q4; doc 08 §4C.4 criterion 18; owner
 * decisions of 2026-11-15: posting amount and accounts, assessment link, missing recipient), on
 * snapshots built in memory: consistent data gives no mismatch, and each kind of inconsistency
 * gives exactly its own mismatch, with ids only.
 */
class ReconcilerTest {

  private static final LocalDate DAY = LocalDate.of(2026, 11, 14);

  /** A balanced two-entry posting of {@code amount}. */
  private static Posting posting(
      UUID id, String reference, String type, String amount, UUID debit, UUID credit) {
    BigDecimal value = new BigDecimal(amount);
    return new Posting(id, reference, type, 2, value, value, Set.of(debit), Set.of(credit));
  }

  /** A mutable snapshot, consistent until a test breaks it. */
  private static final class Data {
    final List<WalletBalances> wallets = new ArrayList<>();
    final List<CompletedMovement> deposits = new ArrayList<>();
    final List<CompletedMovement> withdrawals = new ArrayList<>();
    final List<ReservedUsage> usage = new ArrayList<>();
    final List<CompletedTransfer> completed = new ArrayList<>();
    final List<HeldSum> held = new ArrayList<>();
    final List<HeldTransferOutcome> everHeld = new ArrayList<>();
    final List<ReviewOutcome> reviews = new ArrayList<>();
    final List<AccountTotals> accounts = new ArrayList<>();
    final List<Posting> postings = new ArrayList<>();
    final UUID clearing = UUID.randomUUID();
    BigDecimal clearingDebits = BigDecimal.ZERO;
    BigDecimal clearingCredits = BigDecimal.ZERO;

    /** The recipient of every hold: an existing wallet with nothing in it. */
    final UUID payee = UUID.randomUUID();

    Data() {
      wallets.add(new WalletBalances(payee, BigDecimal.ZERO, BigDecimal.ZERO));
      accounts.add(new AccountTotals(UUID.randomUUID(), payee, BigDecimal.ZERO, BigDecimal.ZERO));
    }

    List<ReconciliationMismatch> compare() {
      List<AccountTotals> all = new ArrayList<>(accounts);
      all.add(new AccountTotals(clearing, null, clearingDebits, clearingCredits, true));
      return Reconciler.compare(
          new Reconciler.Snapshot(
              wallets,
              deposits,
              withdrawals,
              usage,
              completed,
              held,
              everHeld,
              reviews,
              all,
              postings));
    }

    UUID accountOf(UUID wallet) {
      return accounts.stream()
          .filter(a -> wallet.equals(a.walletId()))
          .findFirst()
          .orElseThrow()
          .accountId();
    }

    int indexOf(UUID wallet) {
      for (int i = 0; i < wallets.size(); i++) {
        if (wallets.get(i).walletId().equals(wallet)) {
          return i;
        }
      }
      throw new IllegalArgumentException("no such wallet");
    }

    /** A wallet with its account; a deposit of {@code funded}; a hold of {@code onHold}. */
    UUID wallet(String funded, String onHold) {
      UUID wallet = UUID.randomUUID();
      UUID account = UUID.randomUUID();
      BigDecimal total = new BigDecimal(funded);
      BigDecimal reserved = new BigDecimal(onHold);
      wallets.add(new WalletBalances(wallet, total.subtract(reserved), reserved));
      accounts.add(new AccountTotals(account, wallet, BigDecimal.ZERO, total));
      clearingDebits = clearingDebits.add(total);
      String reference = "DEP-" + UUID.randomUUID();
      deposits.add(new CompletedMovement(UUID.randomUUID(), wallet, reference, total));
      postings.add(posting(UUID.randomUUID(), reference, "DEPOSIT", funded, clearing, account));
      if (reserved.signum() > 0) {
        held.add(new HeldSum(wallet, payee, DAY, reserved));
        usage.add(new ReservedUsage(wallet, "TRANSFER", DAY, reserved));
        everHeld.add(
            new HeldTransferOutcome(UUID.randomUUID(), "PENDING_REVIEW", null, UUID.randomUUID()));
      }
      return wallet;
    }

    /**
     * A sender funded with {@code funded} that completed a transfer of {@code sent} to a new
     * recipient wallet; returns the transfer id.
     */
    UUID transfer(String funded, String sent) {
      BigDecimal in = new BigDecimal(funded);
      BigDecimal out = new BigDecimal(sent);
      UUID sender = UUID.randomUUID();
      UUID senderAccount = UUID.randomUUID();
      UUID receiver = UUID.randomUUID();
      UUID receiverAccount = UUID.randomUUID();
      wallets.add(new WalletBalances(sender, in.subtract(out), BigDecimal.ZERO));
      accounts.add(new AccountTotals(senderAccount, sender, out, in));
      wallets.add(new WalletBalances(receiver, out, BigDecimal.ZERO));
      accounts.add(new AccountTotals(receiverAccount, receiver, BigDecimal.ZERO, out));
      clearingDebits = clearingDebits.add(in);
      String deposit = "DEP-" + UUID.randomUUID();
      deposits.add(new CompletedMovement(UUID.randomUUID(), sender, deposit, in));
      postings.add(posting(UUID.randomUUID(), deposit, "DEPOSIT", funded, clearing, senderAccount));
      UUID transfer = UUID.randomUUID();
      String reference = "TRF-" + UUID.randomUUID();
      completed.add(new CompletedTransfer(transfer, reference, out, sender, receiver));
      postings.add(
          posting(UUID.randomUUID(), reference, "TRANSFER", sent, senderAccount, receiverAccount));
      return transfer;
    }

    /** A decided held transfer and its review row, both naming the same assessment. */
    UUID decided(String status, String category, String action, String decidedBy) {
      UUID transfer = UUID.randomUUID();
      UUID assessment = UUID.randomUUID();
      everHeld.add(new HeldTransferOutcome(transfer, status, category, assessment));
      reviews.add(new ReviewOutcome(UUID.randomUUID(), transfer, assessment, action, decidedBy));
      return transfer;
    }
  }

  private static List<Kind> kinds(List<ReconciliationMismatch> mismatches) {
    return mismatches.stream().map(ReconciliationMismatch::kind).toList();
  }

  @Test
  void consistentDataHasNoMismatch() {
    Data data = new Data();
    data.wallet("100.00", "0.00");
    UUID withdrawer = data.wallet("250.50", "13.00");
    data.transfer("500.00", "120.50");
    // A withdrawal: DEBIT the customer account, CREDIT the clearing account.
    UUID account = data.accountOf(withdrawer);
    int index = data.indexOf(withdrawer);
    data.wallets.set(
        index, new WalletBalances(withdrawer, new BigDecimal("137.50"), new BigDecimal("13.00")));
    data.accounts.set(
        data.accounts.indexOf(
            data.accounts.stream().filter(a -> a.accountId().equals(account)).findFirst().get()),
        new AccountTotals(account, withdrawer, new BigDecimal("100.00"), new BigDecimal("250.50")));
    data.clearingCredits = data.clearingCredits.add(new BigDecimal("100.00"));
    data.withdrawals.add(
        new CompletedMovement(UUID.randomUUID(), withdrawer, "WDR-A", new BigDecimal("100")));
    data.postings.add(
        posting(UUID.randomUUID(), "WDR-A", "WITHDRAWAL", "100.00", account, data.clearing));
    // The seven D2 cases, each with its transfer (6 and 7 are an EXPIRE by the system too).
    data.decided("COMPLETED", null, "APPROVE", "STAFF");
    data.decided("REJECTED", "REVIEW_DECLINED", "REJECT", "STAFF");
    data.decided("REJECTED", "WALLET_UNAVAILABLE", "REJECT", "SYSTEM");
    data.decided("CANCELLED", null, "CANCEL", "CUSTOMER");
    data.decided("EXPIRED", null, "EXPIRE", "SYSTEM");
    data.decided("EXPIRED", null, "EXPIRE", "SYSTEM");
    data.decided("EXPIRED", null, "EXPIRE", "SYSTEM");

    assertThat(data.compare()).isEmpty();
  }

  @Test
  void anEmptyLedgerHasNoMismatch() {
    assertThat(new Data().compare()).isEmpty();
  }

  // Check 1 -------------------------------------------------------------------------------------

  @Test
  void aWalletWhoseTotalDiffersFromItsLedgerBalanceIsCheck1() {
    Data data = new Data();
    UUID wallet = data.wallet("100.00", "0.00");
    data.wallets.set(
        data.indexOf(wallet),
        new WalletBalances(wallet, new BigDecimal("100.01"), BigDecimal.ZERO));

    List<ReconciliationMismatch> found = data.compare();

    assertThat(kinds(found)).containsExactly(Kind.WALLET_BALANCE_DIFFERS);
    assertThat(found.getFirst().check()).isEqualTo("1");
    // Exactly the ids: no amount or balance can be in the line.
    assertThat(found.getFirst().describeIds())
        .isEqualTo("walletId=" + wallet + ", ledgerAccountId=" + data.accountOf(wallet));
  }

  @Test
  void availablePlusReservedIsComparedSoAHoldIsNotAMismatch() {
    Data data = new Data();
    data.wallet("100.00", "40.00");

    assertThat(data.compare()).isEmpty();
  }

  @Test
  void aWalletWithoutAccountAndAnAccountWithoutWalletAreCheck1() {
    Data data = new Data();
    UUID wallet = UUID.randomUUID();
    data.wallets.add(new WalletBalances(wallet, BigDecimal.ZERO, BigDecimal.ZERO));
    UUID orphanAccount = UUID.randomUUID();
    UUID missingWallet = UUID.randomUUID();
    data.accounts.add(
        new AccountTotals(orphanAccount, missingWallet, BigDecimal.ZERO, BigDecimal.ZERO));

    List<ReconciliationMismatch> found = data.compare();

    assertThat(kinds(found))
        .containsExactly(Kind.WALLET_WITHOUT_LEDGER_ACCOUNT, Kind.LEDGER_ACCOUNT_WITHOUT_WALLET);
    assertThat(found.get(0).ids()).isEqualTo(Map.of("walletId", wallet));
    assertThat(found.get(1).ids())
        .containsEntry("ledgerAccountId", orphanAccount)
        .containsEntry("walletId", missingWallet);
  }

  // Check 2 -------------------------------------------------------------------------------------

  @ParameterizedTest
  @CsvSource({"DEPOSIT", "WITHDRAWAL", "TRANSFER"})
  void aCompletedMovementWithoutPostingIsCheck2(String type) {
    Data data = new Data();
    UUID id = UUID.randomUUID();
    BigDecimal amount = new BigDecimal("5.00");
    switch (type) {
      case "DEPOSIT" ->
          data.deposits.add(new CompletedMovement(id, UUID.randomUUID(), "DEP-X", amount));
      case "WITHDRAWAL" ->
          data.withdrawals.add(new CompletedMovement(id, UUID.randomUUID(), "WDR-X", amount));
      default ->
          data.completed.add(
              new CompletedTransfer(id, "TRF-X", amount, UUID.randomUUID(), UUID.randomUUID()));
    }

    List<ReconciliationMismatch> found = data.compare();

    assertThat(kinds(found)).containsExactly(Kind.MOVEMENT_NOT_POSTED);
    assertThat(found.getFirst().check()).isEqualTo("2");
    assertThat(found.getFirst().ids()).isEqualTo(Map.of("recordType", type, "recordId", id));
  }

  @Test
  void aReferencePostedAsAnotherTypeIsCheck2AndThePostingIsUnclaimed() {
    Data data = new Data();
    UUID withdrawal = UUID.randomUUID();
    UUID posting = UUID.randomUUID();
    data.withdrawals.add(
        new CompletedMovement(withdrawal, UUID.randomUUID(), "REF-1", new BigDecimal("9.00")));
    data.postings.add(
        posting(posting, "REF-1", "DEPOSIT", "1.00", data.clearing, UUID.randomUUID()));

    List<ReconciliationMismatch> found = data.compare();

    // Another type: neither its amount nor its accounts are compared.
    assertThat(kinds(found))
        .containsExactly(Kind.MOVEMENT_POSTED_AS_OTHER_TYPE, Kind.POSTING_WITHOUT_MOVEMENT);
    assertThat(found.get(0).ids())
        .containsEntry("recordType", "WITHDRAWAL")
        .containsEntry("recordId", withdrawal)
        .containsEntry("ledgerTransactionId", posting);
    assertThat(found.get(1).ids()).isEqualTo(Map.of("ledgerTransactionId", posting));
  }

  @Test
  void aPostingWithoutBusinessRecordIsCheck2WhateverItsReference() {
    Data data = new Data();
    UUID posting = UUID.randomUUID();
    // The ITs' own TST- postings are reported like any other: there is no test prefix in code.
    data.postings.add(
        posting(
            posting, "TST-" + UUID.randomUUID(), "DEPOSIT", "1.00", data.clearing, data.clearing));

    assertThat(data.compare())
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.kind()).isEqualTo(Kind.POSTING_WITHOUT_MOVEMENT);
              assertThat(m.ids()).isEqualTo(Map.of("ledgerTransactionId", posting));
            });
  }

  @ParameterizedTest
  @ValueSource(strings = {"DEPOSIT", "WITHDRAWAL", "TRANSFER"})
  void aPostingOfAnotherAmountIsCheck2WithIdsOnly(String type) {
    Data data = new Data();
    UUID id = UUID.randomUUID();
    UUID posting = UUID.randomUUID();
    UUID sender = data.wallet("0.00", "0.00");
    BigDecimal amount = new BigDecimal("7.00");
    switch (type) {
      case "DEPOSIT" -> data.deposits.add(new CompletedMovement(id, sender, "REF-A", amount));
      case "WITHDRAWAL" -> data.withdrawals.add(new CompletedMovement(id, sender, "REF-A", amount));
      default -> data.completed.add(new CompletedTransfer(id, "REF-A", amount, sender, data.payee));
    }
    // The accounts of each type's template, so only the amount is wrong.
    UUID account = data.accountOf(sender);
    UUID debit =
        switch (type) {
          case "DEPOSIT" -> data.clearing;
          default -> account;
        };
    UUID credit =
        switch (type) {
          case "DEPOSIT" -> account;
          case "WITHDRAWAL" -> data.clearing;
          default -> data.accountOf(data.payee);
        };
    data.postings.add(posting(posting, "REF-A", type, "7.01", debit, credit));

    List<ReconciliationMismatch> found = data.compare();

    assertThat(kinds(found)).containsExactly(Kind.POSTING_AMOUNT_DIFFERS);
    assertThat(found.getFirst().check()).isEqualTo("2");
    assertThat(found.getFirst().describeIds())
        .isEqualTo("recordType=" + type + ", recordId=" + id + ", ledgerTransactionId=" + posting);
  }

  @Test
  void anUnbalancedPostingWhoseDebitsMatchIsStillAnAmountMismatch() {
    Data data = new Data();
    UUID deposit = UUID.randomUUID();
    UUID posting = UUID.randomUUID();
    data.deposits.add(
        new CompletedMovement(deposit, UUID.randomUUID(), "DEP-U", new BigDecimal("3.00")));
    data.postings.add(
        new Posting(
            posting,
            "DEP-U",
            "DEPOSIT",
            1,
            new BigDecimal("3.00"),
            BigDecimal.ZERO,
            Set.of(data.clearing),
            Set.of()));

    // Its deposit's wallet has no account and nothing is credited: the accounts differ too.
    assertThat(kinds(data.compare()))
        .containsExactly(
            Kind.POSTING_AMOUNT_DIFFERS, Kind.POSTING_ACCOUNTS_DIFFER, Kind.TRANSACTION_UNBALANCED);
  }

  // Check 2: deposit and withdrawal accounts (owner, 2026-11-18) -------------------------------

  /**
   * A deposit posted to another customer's account, or from another account than the cash clearing
   * account, and a withdrawal the same way round: each is {@code POSTING_ACCOUNTS_DIFFER} with ids
   * only, and nothing else (the amounts match).
   */
  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({
    "DEPOSIT, customer side is another customer",
    "DEPOSIT, system side is not cash clearing",
    "DEPOSIT, system side is a customer",
    "DEPOSIT, sides swapped",
    "WITHDRAWAL, customer side is another customer",
    "WITHDRAWAL, system side is not cash clearing",
    "WITHDRAWAL, system side is a customer",
    "WITHDRAWAL, sides swapped"
  })
  void aDepositOrWithdrawalPostedBetweenOtherAccountsIsCheck2(String type, String variant) {
    Data data = new Data();
    UUID wallet = data.wallet("0.00", "0.00");
    UUID own = data.accountOf(wallet);
    UUID other = data.accountOf(data.payee);
    UUID suspense = UUID.randomUUID();
    data.accounts.add(new AccountTotals(suspense, null, BigDecimal.ZERO, BigDecimal.ZERO));
    UUID id = UUID.randomUUID();
    UUID posting = UUID.randomUUID();
    boolean deposit = "DEPOSIT".equals(type);
    UUID customer = variant.startsWith("customer side") ? other : own;
    UUID system =
        switch (variant) {
          case "system side is not cash clearing" -> suspense;
          case "system side is a customer" -> other;
          default -> data.clearing;
        };
    UUID debit = deposit ? system : customer;
    UUID credit = deposit ? customer : system;
    if (variant.equals("sides swapped")) {
      UUID swap = debit;
      debit = credit;
      credit = swap;
    }
    (deposit ? data.deposits : data.withdrawals)
        .add(new CompletedMovement(id, wallet, "REF-B", new BigDecimal("5.00")));
    data.postings.add(posting(posting, "REF-B", type, "5.00", debit, credit));

    List<ReconciliationMismatch> found = data.compare();

    assertThat(kinds(found)).containsExactly(Kind.POSTING_ACCOUNTS_DIFFER);
    assertThat(found.getFirst().check()).isEqualTo("2");
    assertThat(found.getFirst().describeIds())
        .isEqualTo("recordType=" + type + ", recordId=" + id + ", ledgerTransactionId=" + posting);
  }

  @Test
  void aDepositIsPostedFromCashClearingToItsWalletAndAWithdrawalTheReverse() {
    Data data = new Data();
    UUID wallet = data.wallet("0.00", "0.00");
    UUID account = data.accountOf(wallet);
    data.deposits.add(
        new CompletedMovement(UUID.randomUUID(), wallet, "DEP-C", new BigDecimal("5.00")));
    data.postings.add(
        posting(UUID.randomUUID(), "DEP-C", "DEPOSIT", "5.00", data.clearing, account));
    data.withdrawals.add(
        new CompletedMovement(UUID.randomUUID(), wallet, "WDR-C", new BigDecimal("5.00")));
    data.postings.add(
        posting(UUID.randomUUID(), "WDR-C", "WITHDRAWAL", "5.00", account, data.clearing));

    assertThat(data.compare()).isEmpty();
  }

  @Test
  void withoutACashClearingAccountNoDepositOrWithdrawalCanMatch() {
    Data data = new Data();
    data.wallet("0.00", "0.00");
    UUID deposit = data.deposits.getFirst().id();
    // The same snapshot, with the clearing account no longer flagged as such.
    List<AccountTotals> accounts = new ArrayList<>(data.accounts);
    accounts.add(new AccountTotals(data.clearing, null, data.clearingDebits, data.clearingCredits));
    List<ReconciliationMismatch> found =
        Reconciler.compare(
            new Reconciler.Snapshot(
                data.wallets,
                data.deposits,
                data.withdrawals,
                data.usage,
                data.completed,
                data.held,
                data.everHeld,
                data.reviews,
                accounts,
                data.postings));

    assertThat(found)
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.kind()).isEqualTo(Kind.POSTING_ACCOUNTS_DIFFER);
              assertThat(m.ids()).containsEntry("recordId", deposit);
            });
  }

  // Check 1: duplicate customer account (owner, 2026-11-18) ------------------------------------

  @Test
  void aSecondCustomerAccountForAWalletIsReportedNotOverwritten() {
    Data data = new Data();
    UUID wallet = data.wallet("100.00", "0.00");
    UUID first = data.accountOf(wallet);
    UUID second = UUID.randomUUID();
    data.accounts.add(new AccountTotals(second, wallet, BigDecimal.ZERO, BigDecimal.ZERO));

    List<ReconciliationMismatch> found = data.compare();

    // The first account still matches the wallet and the deposit; only the second is reported.
    assertThat(found)
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.kind()).isEqualTo(Kind.DUPLICATE_CUSTOMER_ACCOUNT);
              assertThat(m.check()).isEqualTo("1");
              assertThat(m.ids())
                  .containsExactly(
                      Map.entry("walletId", wallet),
                      Map.entry("ledgerAccountId", first),
                      Map.entry("otherLedgerAccountId", second));
            });
  }

  @Test
  void aCustomerAccountIsNeverTheCashClearingAccount() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () ->
                new AccountTotals(
                    UUID.randomUUID(), UUID.randomUUID(), BigDecimal.ZERO, BigDecimal.ZERO, true))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void amountsAreComparedWithoutRegardToScale() {
    Data data = new Data();
    UUID wallet = UUID.randomUUID();
    UUID account = UUID.randomUUID();
    data.wallets.add(new WalletBalances(wallet, new BigDecimal("10.0"), new BigDecimal("0")));
    data.accounts.add(new AccountTotals(account, wallet, BigDecimal.ZERO, new BigDecimal("10.00")));
    data.clearingDebits = new BigDecimal("10");
    data.deposits.add(
        new CompletedMovement(UUID.randomUUID(), wallet, "DEP-S", new BigDecimal("10")));
    data.postings.add(
        posting(UUID.randomUUID(), "DEP-S", "DEPOSIT", "10.00", data.clearing, account));

    assertThat(data.compare()).isEmpty();
  }

  @Test
  void aTransferPostedBetweenOtherAccountsIsCheck2() {
    Data data = new Data();
    UUID transfer = data.transfer("100.00", "30.00");
    CompletedTransfer record = data.completed.getFirst();
    Posting real =
        data.postings.stream()
            .filter(p -> p.reference().equals(record.reference()))
            .findFirst()
            .get();
    // The same amount, but credited to the payee instead of the transfer's receiver.
    data.postings.set(
        data.postings.indexOf(real),
        posting(
            real.id(),
            real.reference(),
            "TRANSFER",
            "30.00",
            data.accountOf(record.senderWalletId()),
            data.accountOf(data.payee)));

    List<ReconciliationMismatch> found = data.compare();

    assertThat(kinds(found)).containsExactly(Kind.POSTING_ACCOUNTS_DIFFER);
    assertThat(found.getFirst().check()).isEqualTo("2");
    assertThat(found.getFirst().describeIds())
        .isEqualTo(
            "recordType=TRANSFER, recordId=" + transfer + ", ledgerTransactionId=" + real.id());
  }

  @Test
  void aTransferPostingWithAnExtraAccountOrTheSidesSwappedIsCheck2() {
    for (boolean swapped : new boolean[] {false, true}) {
      Data data = new Data();
      data.transfer("100.00", "30.00");
      CompletedTransfer record = data.completed.getFirst();
      Posting real =
          data.postings.stream()
              .filter(p -> p.reference().equals(record.reference()))
              .findFirst()
              .get();
      UUID sender = data.accountOf(record.senderWalletId());
      UUID receiver = data.accountOf(record.receiverWalletId());
      Posting changed =
          new Posting(
              real.id(),
              real.reference(),
              "TRANSFER",
              swapped ? 2 : 3,
              real.debits(),
              real.credits(),
              swapped ? Set.of(receiver) : Set.of(sender, data.clearing),
              swapped ? Set.of(sender) : Set.of(receiver));
      data.postings.set(data.postings.indexOf(real), changed);

      assertThat(kinds(data.compare()))
          .as("swapped=" + swapped)
          .containsExactly(Kind.POSTING_ACCOUNTS_DIFFER);
    }
  }

  @Test
  void aTransferWhoseWalletHasNoLedgerAccountCannotMatchItsPosting() {
    Data data = new Data();
    data.transfer("100.00", "30.00");
    CompletedTransfer record = data.completed.getFirst();
    data.completed.set(
        0,
        new CompletedTransfer(
            record.id(),
            record.reference(),
            record.amount(),
            UUID.randomUUID(),
            record.receiverWalletId()));

    assertThat(kinds(data.compare())).containsExactly(Kind.POSTING_ACCOUNTS_DIFFER);
  }

  // Checks 3 and 4 ------------------------------------------------------------------------------

  @Test
  void aReservedBalanceThatDiffersFromTheHeldTransfersIsCheck3Only() {
    Data data = new Data();
    UUID wallet = data.wallet("100.00", "40.00");
    // Moved within the wallet, so check 1 still holds; the hold and the usage still say 40.00.
    data.wallets.set(
        data.indexOf(wallet),
        new WalletBalances(wallet, new BigDecimal("59.00"), new BigDecimal("41.00")));

    List<ReconciliationMismatch> found = data.compare();

    assertThat(kinds(found)).containsExactly(Kind.RESERVED_BALANCE_DIFFERS);
    assertThat(found.getFirst().check()).isEqualTo("3");
    assertThat(found.getFirst().ids()).isEqualTo(Map.of("walletId", wallet));
  }

  @Test
  void aReservedBalanceWithoutAnyHeldTransferIsCheck3() {
    Data data = new Data();
    UUID wallet = data.wallet("100.00", "0.00");
    data.wallets.set(
        data.indexOf(wallet),
        new WalletBalances(wallet, new BigDecimal("90.00"), new BigDecimal("10.00")));

    assertThat(kinds(data.compare())).containsExactly(Kind.RESERVED_BALANCE_DIFFERS);
  }

  @Test
  void heldTransfersOfAnUnknownSenderWalletAreCheck3AndCheck4() {
    Data data = new Data();
    UUID unknown = UUID.randomUUID();
    data.held.add(new HeldSum(unknown, data.payee, DAY, new BigDecimal("5.00")));

    List<ReconciliationMismatch> found = data.compare();

    assertThat(kinds(found))
        .containsExactly(Kind.HOLD_OF_UNKNOWN_WALLET, Kind.RESERVED_USAGE_DIFFERS);
    assertThat(found.get(0).ids())
        .containsExactly(Map.entry("walletId", unknown), Map.entry("side", "SENDER"));
  }

  @Test
  void heldTransfersToAnUnknownRecipientWalletAreCheck3Once() {
    Data data = new Data();
    UUID sender = data.wallet("100.00", "40.00");
    UUID unknown = UUID.randomUUID();
    // Two holds to the missing recipient, on two days, each with its reservation.
    data.held.set(0, new HeldSum(sender, unknown, DAY, new BigDecimal("15.00")));
    data.usage.set(0, new ReservedUsage(sender, "TRANSFER", DAY, new BigDecimal("15.00")));
    data.held.add(new HeldSum(sender, unknown, DAY.plusDays(1), new BigDecimal("25.00")));
    data.usage.add(new ReservedUsage(sender, "TRANSFER", DAY.plusDays(1), new BigDecimal("25.00")));

    List<ReconciliationMismatch> found = data.compare();

    assertThat(found)
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.kind()).isEqualTo(Kind.HOLD_OF_UNKNOWN_WALLET);
              assertThat(m.check()).isEqualTo("3");
              assertThat(m.ids())
                  .containsExactly(Map.entry("walletId", unknown), Map.entry("side", "RECIPIENT"));
            });
  }

  @Test
  void aDaysReservedUsageThatDiffersIsCheck4WithTheWalletOperationAndStoredDay() {
    Data data = new Data();
    UUID wallet = data.wallet("100.00", "40.00");
    data.usage.set(0, new ReservedUsage(wallet, "TRANSFER", DAY, new BigDecimal("40.01")));

    List<ReconciliationMismatch> found = data.compare();

    assertThat(kinds(found)).containsExactly(Kind.RESERVED_USAGE_DIFFERS);
    assertThat(found.getFirst().check()).isEqualTo("4");
    assertThat(found.getFirst().ids())
        .containsExactly(
            Map.entry("walletId", wallet),
            Map.entry("operation", "TRANSFER"),
            Map.entry("limitDate", DAY));
  }

  @Test
  void aHoldWhoseDayHasNoReservedUsageIsCheck4() {
    Data data = new Data();
    UUID wallet = data.wallet("100.00", "40.00");
    data.usage.clear();

    assertThat(data.compare())
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.kind()).isEqualTo(Kind.RESERVED_USAGE_DIFFERS);
              assertThat(m.ids()).containsEntry("walletId", wallet).containsEntry("limitDate", DAY);
            });
  }

  @Test
  void usageIsComparedPerStoredDaySoTheSameTotalOnAnotherDayIsCheck4ForBothDays() {
    Data data = new Data();
    UUID wallet = data.wallet("100.00", "40.00");
    // The hold's stored day is DAY; the usage was reserved on the day before (Y5: never
    // recomputed).
    data.usage.set(
        0, new ReservedUsage(wallet, "TRANSFER", DAY.minusDays(1), new BigDecimal("40.00")));

    List<ReconciliationMismatch> found = data.compare();

    assertThat(kinds(found))
        .containsExactly(Kind.RESERVED_USAGE_DIFFERS, Kind.RESERVED_USAGE_DIFFERS);
    assertThat(found)
        .extracting(m -> m.ids().get("limitDate"))
        .containsExactlyInAnyOrder(DAY, DAY.minusDays(1));
  }

  @Test
  void holdsOnSeveralStoredDaysAreSummedPerDayAndForTheWallet() {
    Data data = new Data();
    UUID wallet = data.wallet("100.00", "0.00");
    data.wallets.set(
        data.indexOf(wallet),
        new WalletBalances(wallet, new BigDecimal("70.00"), new BigDecimal("30.00")));
    data.held.add(new HeldSum(wallet, data.payee, DAY, new BigDecimal("10.00")));
    data.held.add(new HeldSum(wallet, data.payee, DAY.plusDays(1), new BigDecimal("20.00")));
    data.usage.add(new ReservedUsage(wallet, "TRANSFER", DAY, new BigDecimal("10.00")));
    data.usage.add(new ReservedUsage(wallet, "TRANSFER", DAY.plusDays(1), new BigDecimal("20.00")));

    assertThat(data.compare()).isEmpty();
  }

  @Test
  void aReservationOnAnotherOperationIsCheck4() {
    Data data = new Data();
    UUID wallet = data.wallet("100.00", "0.00");
    data.usage.add(new ReservedUsage(wallet, "DEPOSIT", DAY, new BigDecimal("1.00")));

    assertThat(data.compare())
        .singleElement()
        .satisfies(m -> assertThat(m.ids()).containsEntry("operation", "DEPOSIT"));
  }

  // Check 5 -------------------------------------------------------------------------------------

  @Test
  void aStaffRejectionPairedWithWalletUnavailableIsCheck5() {
    Data data = new Data();
    UUID transfer = data.decided("REJECTED", "WALLET_UNAVAILABLE", "REJECT", "STAFF");

    List<ReconciliationMismatch> found = data.compare();

    assertThat(kinds(found)).containsExactly(Kind.REVIEW_DISAGREES_WITH_TRANSFER);
    assertThat(found.getFirst().check()).isEqualTo("5");
    assertThat(found.getFirst().ids())
        .containsEntry("transferId", transfer)
        .containsKey("reviewId");
  }

  @ParameterizedTest(name = "{2}/{3} with {0} {1}")
  @CsvSource(
      nullValues = "-",
      value = {
        // A system rejection with the staff category, and the reverse of the case above.
        "REJECTED, REVIEW_DECLINED, REJECT, SYSTEM",
        // An action that disagrees with the final status.
        "EXPIRED, -, CANCEL, CUSTOMER",
        "CANCELLED, -, EXPIRE, SYSTEM",
        "COMPLETED, -, REJECT, STAFF",
        "REJECTED, REVIEW_DECLINED, APPROVE, STAFF",
        "EXPIRED, -, APPROVE, STAFF",
        // A source that disagrees with the action (the database refuses these rows; still checked).
        "COMPLETED, -, APPROVE, SYSTEM",
        "CANCELLED, -, CANCEL, STAFF",
        "EXPIRED, -, EXPIRE, STAFF",
        // A status outside the decided ones.
        "BLOCKED, -, APPROVE, STAFF"
      })
  void anOutcomeThatDisagreesWithTheTransferIsCheck5(
      String status, String category, String action, String decidedBy) {
    Data data = new Data();
    data.decided(status, category, action, decidedBy);

    assertThat(kinds(data.compare())).containsExactly(Kind.REVIEW_DISAGREES_WITH_TRANSFER);
  }

  @Test
  void aDecidedTransferWithoutReviewRowIsCheck5() {
    Data data = new Data();
    UUID transfer = UUID.randomUUID();
    data.everHeld.add(new HeldTransferOutcome(transfer, "CANCELLED", null, UUID.randomUUID()));

    assertThat(data.compare())
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.kind()).isEqualTo(Kind.DECIDED_WITHOUT_REVIEW);
              assertThat(m.ids()).isEqualTo(Map.of("transferId", transfer));
            });
  }

  @Test
  void aReviewRowForATransferStillHeldIsCheck5() {
    Data data = new Data();
    UUID transfer = UUID.randomUUID();
    UUID review = UUID.randomUUID();
    UUID assessment = UUID.randomUUID();
    data.everHeld.add(new HeldTransferOutcome(transfer, "PENDING_REVIEW", null, assessment));
    data.reviews.add(new ReviewOutcome(review, transfer, assessment, "CANCEL", "CUSTOMER"));

    assertThat(data.compare())
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.kind()).isEqualTo(Kind.REVIEW_WITHOUT_DECIDED_TRANSFER);
              assertThat(m.ids()).isEqualTo(Map.of("transferId", transfer, "reviewId", review));
            });
  }

  @Test
  void aReviewRowForATransferNeverHeldOrMissingIsCheck5() {
    Data data = new Data();
    // A transfer created COMPLETED or BLOCKED is not among the ever-held ones, nor is a missing
    // one.
    UUID notHeld = UUID.randomUUID();
    data.reviews.add(
        new ReviewOutcome(UUID.randomUUID(), notHeld, UUID.randomUUID(), "APPROVE", "STAFF"));

    assertThat(data.compare())
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.kind()).isEqualTo(Kind.REVIEW_WITHOUT_DECIDED_TRANSFER);
              assertThat(m.ids()).containsEntry("transferId", notHeld);
            });
  }

  @Test
  void aSecondReviewRowForATransferIsReportedNotDropped() {
    Data data = new Data();
    UUID transfer = data.decided("CANCELLED", null, "CANCEL", "CUSTOMER");
    ReviewOutcome first = data.reviews.getFirst();
    UUID second = UUID.randomUUID();
    data.reviews.add(new ReviewOutcome(second, transfer, first.assessmentId(), "EXPIRE", "SYSTEM"));

    List<ReconciliationMismatch> found = data.compare();

    // The first row still agrees with the transfer; the second is reported with both ids.
    assertThat(found)
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.kind()).isEqualTo(Kind.DUPLICATE_REVIEW);
              assertThat(m.check()).isEqualTo("5");
              assertThat(m.ids())
                  .containsExactly(
                      Map.entry("transferId", transfer),
                      Map.entry("reviewId", first.reviewId()),
                      Map.entry("otherReviewId", second));
            });
  }

  @Test
  void aSecondReviewRowIsReportedAndTheFirstStillCompared() {
    Data data = new Data();
    UUID transfer = data.decided("CANCELLED", null, "EXPIRE", "SYSTEM");
    data.reviews.add(
        new ReviewOutcome(
            UUID.randomUUID(),
            transfer,
            data.reviews.getFirst().assessmentId(),
            "CANCEL",
            "CUSTOMER"));

    assertThat(kinds(data.compare()))
        .containsExactly(Kind.DUPLICATE_REVIEW, Kind.REVIEW_DISAGREES_WITH_TRANSFER);
  }

  @Test
  void aReviewRowNamingAnotherAssessmentThanItsTransferIsCheck5() {
    Data data = new Data();
    UUID transfer = data.decided("CANCELLED", null, "CANCEL", "CUSTOMER");
    ReviewOutcome review = data.reviews.getFirst();
    UUID other = UUID.randomUUID();
    data.reviews.set(
        0,
        new ReviewOutcome(review.reviewId(), transfer, other, review.action(), review.decidedBy()));
    UUID expected = data.everHeld.getFirst().riskAssessmentId();

    List<ReconciliationMismatch> found = data.compare();

    // The outcome itself agrees: only the link is reported.
    assertThat(found)
        .singleElement()
        .satisfies(
            m -> {
              assertThat(m.kind()).isEqualTo(Kind.REVIEW_ASSESSMENT_DIFFERS);
              assertThat(m.check()).isEqualTo("5");
              assertThat(m.ids())
                  .containsExactly(
                      Map.entry("transferId", transfer),
                      Map.entry("reviewId", review.reviewId()),
                      Map.entry("reviewAssessmentId", other),
                      Map.entry("transferAssessmentId", expected));
            });
  }

  @Test
  void theAssessmentLinkIsAlsoCheckedForAReviewOfATransferStillHeld() {
    Data data = new Data();
    UUID transfer = UUID.randomUUID();
    data.everHeld.add(new HeldTransferOutcome(transfer, "PENDING_REVIEW", null, UUID.randomUUID()));
    data.reviews.add(
        new ReviewOutcome(UUID.randomUUID(), transfer, UUID.randomUUID(), "CANCEL", "CUSTOMER"));

    assertThat(kinds(data.compare()))
        .containsExactly(Kind.REVIEW_WITHOUT_DECIDED_TRANSFER, Kind.REVIEW_ASSESSMENT_DIFFERS);
  }

  @Test
  void aStillHeldTransferWithoutReviewRowIsNotAMismatch() {
    Data data = new Data();
    data.everHeld.add(
        new HeldTransferOutcome(UUID.randomUUID(), "PENDING_REVIEW", null, UUID.randomUUID()));

    assertThat(data.compare()).isEmpty();
  }

  // Σ DEBIT = Σ CREDIT --------------------------------------------------------------------------

  @Test
  void anUnbalancedTransactionAndAnUnbalancedLedgerAreSumMismatches() {
    Data data = new Data();
    data.wallet("100.00", "0.00");
    UUID unbalanced = UUID.randomUUID();
    // One entry only, and no business record.
    data.postings.add(
        new Posting(
            unbalanced,
            "TST-1",
            "DEPOSIT",
            1,
            new BigDecimal("0.01"),
            BigDecimal.ZERO,
            Set.of(data.clearing),
            Set.of()));
    data.clearingDebits = data.clearingDebits.add(new BigDecimal("0.01"));

    List<ReconciliationMismatch> found = data.compare();

    assertThat(kinds(found))
        .containsExactly(
            Kind.POSTING_WITHOUT_MOVEMENT,
            Kind.TRANSACTION_UNBALANCED,
            Kind.LEDGER_TOTAL_UNBALANCED);
    assertThat(found.get(1).check()).isEqualTo("SUM");
    assertThat(found.get(1).ids()).isEqualTo(Map.of("ledgerTransactionId", unbalanced));
    assertThat(found.get(2).check()).isEqualTo("SUM");
    assertThat(found.get(2).ids()).isEmpty();
  }

  @Test
  void aBalancedTransactionWithFewerThanTwoEntriesIsUnbalanced() {
    Posting none =
        new Posting(
            UUID.randomUUID(),
            "X",
            "DEPOSIT",
            0,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            Set.of(),
            Set.of());

    assertThat(none.balanced()).isFalse();
    assertThat(
            new Posting(
                    UUID.randomUUID(),
                    "Y",
                    "DEPOSIT",
                    2,
                    new BigDecimal("1.0"),
                    new BigDecimal("1.00"),
                    Set.of(),
                    Set.of())
                .balanced())
        .isTrue();
  }

  // Report and mismatch -------------------------------------------------------------------------

  @Test
  void theReportCountsByCheckAndFindsMismatchesById() {
    UUID wallet = UUID.randomUUID();
    ReconciliationMismatch first =
        new ReconciliationMismatch(Kind.WALLET_BALANCE_DIFFERS, Map.of("walletId", wallet));
    ReconciliationMismatch second =
        new ReconciliationMismatch(Kind.RESERVED_BALANCE_DIFFERS, Map.of("walletId", wallet));
    ReconciliationMismatch other =
        new ReconciliationMismatch(Kind.LEDGER_TOTAL_UNBALANCED, Map.of());
    ReconciliationReport report =
        new ReconciliationReport(true, List.of(first, second, other), Map.of("wallets", 1));

    assertThat(report.involving(wallet)).containsExactly(first, second);
    assertThat(report.countsByCheck())
        .containsExactly(Map.entry("1", 1L), Map.entry("3", 1L), Map.entry("SUM", 1L));
    assertThat(ReconciliationReport.failed().completed()).isFalse();
    assertThat(ReconciliationReport.failed().mismatches()).isEmpty();
  }
}
