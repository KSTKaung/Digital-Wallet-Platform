package com.portfolio.wallet.transfer.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.portfolio.wallet.auth.domain.Role;
import com.portfolio.wallet.ledger.persistence.LedgerHistoryRepository;
import com.portfolio.wallet.ledger.persistence.LedgerHistoryRow;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * {@code GET /api/v1/transactions} end to end (documentations/02 §1.5, §2.4 "MVP history"; doc 06
 * MVP-ADR-11 amendment, MVP-ADR-24 "History"; doc 07 D06; doc 08 §4C.2 Q14, §4C.4 criterion 17).
 * The history is the caller's own customer ledger account: completed movements only, typed by the
 * posting's type and side, newest first with the entry id as tie-break, paged 1–100.
 *
 * <p>Lives with the transfer flow tests because it needs their fixtures: real deposits,
 * withdrawals, transfers in every outcome and staff reviews, all through HTTP.
 */
class TransactionHistoryIT extends TransferReviewFlowSupport {

  static final String HISTORY = "/api/v1/transactions";

  @Autowired LedgerHistoryRepository historyRows;
  @Autowired PlatformTransactionManager transactionManager;

  private static final List<String> FIELDS =
      List.of("reference", "type", "amount", "currency", "postedAt", "ledgerStatus");

  /** The amount at scale 2; a value with a third decimal fails here instead of being rounded. */
  record Item(String reference, String type, BigDecimal amount) {
    Item {
      amount = amount.setScale(2, RoundingMode.UNNECESSARY);
    }
  }

  private MockHttpServletResponse history(Account caller, String query) throws Exception {
    return getAs(HISTORY + query, caller.token());
  }

  private List<Item> items(MockHttpServletResponse response) throws Exception {
    assertThat(response.getStatus()).as("history").isEqualTo(200);
    List<Item> items = new ArrayList<>();
    for (JsonNode item : body(response).get("items")) {
      assertThat(item.propertyNames()).containsExactlyInAnyOrderElementsOf(FIELDS);
      assertThat(item.get("currency").asString()).isEqualTo("THB");
      assertThat(item.get("ledgerStatus").asString()).isEqualTo("POSTED");
      assertThat(item.get("amount").decimalValue()).isPositive();
      items.add(
          new Item(
              item.get("reference").asString(),
              item.get("type").asString(),
              item.get("amount").decimalValue()));
    }
    return items;
  }

  private List<Item> allItems(Account caller) throws Exception {
    return items(history(caller, "?size=100"));
  }

  private MockHttpServletResponse withdraw(Account customer, String amount) throws Exception {
    MockHttpServletResponse response =
        send(
            post("/api/v1/wallet/withdrawals")
                .contentType(MediaType.APPLICATION_JSON)
                .header(KEY_HEADER, UUID.randomUUID().toString())
                .content("{\"amount\":" + amount + ",\"currency\":\"THB\"}"),
            customer.token());
    assertThat(response.getStatus()).as("withdrawal").isEqualTo(201);
    return response;
  }

  private String reference(MockHttpServletResponse response) throws Exception {
    return body(response).get("reference").asString();
  }

  private String depositReference(Account customer) {
    return jdbc.queryForObject(
        "SELECT reference FROM wallet.deposits WHERE customer_id = ? ORDER BY created_at LIMIT 1",
        String.class,
        customer.id());
  }

  private String transferReference(UUID transferId) {
    return (String) transferRow(transferId).get("reference");
  }

  private static Item item(String reference, String type, String amount) {
    return new Item(reference, type, new BigDecimal(amount));
  }

  /** Σ of the items, credits positive: must equal the ledger balance Σ CREDIT − Σ DEBIT. */
  private static BigDecimal signedSum(List<Item> items) {
    return items.stream()
        .map(
            i ->
                i.type().endsWith("_OUT") || "WITHDRAWAL".equals(i.type())
                    ? i.amount().negate()
                    : i.amount())
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  /** The references of the entries on the caller's customer account, read from the ledger. */
  private List<String> ledgerReferences(Account account) {
    return jdbc.queryForList(
        """
        SELECT t.transaction_reference FROM ledger.ledger_entries e
        JOIN ledger.financial_transactions t ON t.id = e.transaction_id
        JOIN ledger.ledger_accounts a ON a.id = e.account_id
        WHERE a.owner_reference = ? AND a.account_type = 'CUSTOMER'
        """,
        String.class,
        walletId(account));
  }

  /**
   * Every outcome of a transfer at once: only postings appear. Held transfers that are cancelled,
   * rejected or expired, a still-held one and a blocked one post nothing; an approved held transfer
   * appears once, at its approval time, ahead of movements made after it was submitted.
   */
  @Test
  void onlyCompletedMovementsAppearTypedAndSignedNewestFirst() throws Exception {
    Account alice = funded("10000.00");
    Account bob = funded("1000.00");
    Account officer = officer();
    // Every hold comes before the first completed alice → bob transfer, which would lower the
    // score of the same amount by the new-recipient weight.
    UUID cancelled = held(alice, bob);
    UUID rejected = held(alice, bob);
    UUID expired = held(alice, bob);
    UUID pending = held(alice, bob);
    UUID approved = held(alice, bob);
    assertThat(transfer(alice, bob, BLOCK).getStatus()).isEqualTo(403);
    assertThat(cancel(cancelled, alice).getStatus()).isEqualTo(200);
    assertThat(rejectReview(rejected, "not plausible", officer).getStatus()).isEqualTo(200);
    overdue(expired, 60);
    assertThat(cancel(expired, alice).getStatus()).isEqualTo(409);
    assertThat(transferRow(expired)).containsEntry("status", "EXPIRED");
    String withdrawal = reference(withdraw(alice, "200.00"));
    String out = reference(transfer(alice, bob, "100.00"));
    String in = reference(transfer(bob, alice, "40.00"));
    assertThat(approveReview(approved, officer).getStatus()).isEqualTo(200);
    assertThat(transferRow(approved)).containsEntry("status", "COMPLETED");
    assertThat(transferRow(pending)).containsEntry("status", "PENDING_REVIEW");

    List<Item> aliceItems = allItems(alice);

    assertThat(aliceItems)
        .containsExactly(
            item(transferReference(approved), "TRANSFER_OUT", HOLD),
            item(in, "TRANSFER_IN", "40.00"),
            item(out, "TRANSFER_OUT", "100.00"),
            item(withdrawal, "WITHDRAWAL", "200.00"),
            item(depositReference(alice), "DEPOSIT", "10000.00"));
    assertThat(allItems(bob))
        .containsExactly(
            item(transferReference(approved), "TRANSFER_IN", HOLD),
            item(in, "TRANSFER_OUT", "40.00"),
            item(out, "TRANSFER_IN", "100.00"),
            item(depositReference(bob), "DEPOSIT", "1000.00"));
    List<String> shown = aliceItems.stream().map(Item::reference).toList();
    for (UUID hidden : List.of(cancelled, rejected, expired, pending)) {
      assertThat(shown).doesNotContain(transferReference(hidden));
    }
    for (Map<String, Object> blocked : transfersOf(alice)) {
      if ("BLOCKED".equals(blocked.get("status"))) {
        assertThat(shown).doesNotContain((String) blocked.get("reference"));
      }
    }
    // Cross-check: the items are the account's ledger entries, and their sum is its balance.
    for (Account account : List.of(alice, bob)) {
      List<Item> items = allItems(account);
      assertThat(items.stream().map(Item::reference).toList())
          .containsExactlyInAnyOrderElementsOf(ledgerReferences(account));
      assertThat(signedSum(items)).isEqualByComparingTo(ledgerBalance(account));
      assertTotalMatchesLedger(account);
    }
    assertThat(signedSum(aliceItems)).isEqualByComparingTo("8440.00");
  }

  @Test
  void amountsHaveTwoDecimalsAndPostedAtIsTheLedgerTime() throws Exception {
    Account customer = funded("1000.50");
    withdraw(customer, "100.5");

    MockHttpServletResponse response = history(customer, "");

    String content = response.getContentAsString();
    assertThat(content).contains("\"amount\":100.50,").contains("\"amount\":1000.50,");
    JsonNode items = body(response).get("items");
    List<Instant> ledgerTimes =
        jdbc
            .queryForList(
                """
            SELECT e.created_at FROM ledger.ledger_entries e
            JOIN ledger.ledger_accounts a ON a.id = e.account_id
            WHERE a.owner_reference = ? ORDER BY e.created_at DESC
            """,
                java.time.OffsetDateTime.class,
                walletId(customer))
            .stream()
            .map(java.time.OffsetDateTime::toInstant)
            .toList();
    assertThat(
            List.of(
                Instant.parse(items.get(0).get("postedAt").asString()),
                Instant.parse(items.get(1).get("postedAt").asString())))
        .isEqualTo(ledgerTimes);
    assertThat(items.get(0).get("type").asString()).isEqualTo("WITHDRAWAL");
  }

  /** Another customer's entries never appear, not even those of a shared transfer. */
  @Test
  void aCustomerSeesOnlyTheirOwnEntries() throws Exception {
    Account alice = funded("500.00");
    Account carol = funded("700.00");
    Account stranger = withWallet();
    String shared = reference(transfer(carol, alice, "70.00"));

    assertThat(allItems(alice))
        .containsExactly(
            item(shared, "TRANSFER_IN", "70.00"),
            item(depositReference(alice), "DEPOSIT", "500.00"));
    assertThat(allItems(alice).stream().map(Item::reference))
        .doesNotContain(depositReference(carol));
    assertThat(allItems(carol))
        .containsExactly(
            item(shared, "TRANSFER_OUT", "70.00"),
            item(depositReference(carol), "DEPOSIT", "700.00"));
    MockHttpServletResponse empty = history(stranger, "");
    assertThat(items(empty)).isEmpty();
    assertThat(body(empty).get("page").get("totalElements").asLong()).isZero();
    assertThat(empty.getContentAsString())
        .doesNotContain(alice.id().toString())
        .doesNotContain(walletId(alice).toString())
        .doesNotContain(numberOf(alice));
  }

  /**
   * Two postings of one database transaction share {@code created_at}; the entry id (PostgreSQL's
   * uuid order, descending) decides, not the insertion order, and pages never overlap or skip. The
   * two postings cancel out, so the wallet snapshot still equals the ledger.
   *
   * <p><b>What this can and cannot prove:</b> the page query is served by a backward scan of V2's
   * {@code ix_ledger_entries_account_created (account_id, created_at, id)}, which yields {@code id
   * DESC} within a tie by itself, so the HTTP part alone cannot prove the {@code ORDER BY ... e.id
   * DESC} clause. The second part therefore reads the same page through {@link
   * LedgerHistoryRepository} with index scans disabled, so PostgreSQL must sort by the clause; the
   * rows were inserted in the opposite order to their ids, so without the tie-break the heap order
   * would show. {@code LedgerHistoryRepositoryTest} also pins the clause's text. Checked on
   * 2026-11-14 by removing {@code e.id DESC}: the paged HTTP reads still passed, and the no-index
   * read failed.
   */
  @Test
  void tiesAreBrokenByTheEntryIdAndPagesDoNotOverlap() throws Exception {
    Account customer = funded("100.00");
    UUID account =
        jdbc.queryForObject(
            "SELECT id FROM ledger.ledger_accounts WHERE owner_reference = ?",
            UUID.class,
            walletId(customer));
    UUID clearing = cashClearingAccountId();
    String tail = UUID.randomUUID().toString().substring(8);
    UUID high = UUID.fromString("ffffffff" + tail);
    UUID low = UUID.fromString("00000000" + tail);
    String depositRef = "TST-" + UUID.randomUUID();
    String withdrawalRef = "TST-" + UUID.randomUUID();
    inTransaction(
        c -> {
          // Inserted first, with the lower entry id: it must come second.
          UUID withdrawal = ledgerTransaction(c, withdrawalRef, "WITHDRAWAL");
          insert(c, "ledger.ledger_entries", entry(low, withdrawal, account, "DEBIT"));
          insertEntry(c, withdrawal, clearing, "CREDIT", "5.00");
          UUID deposit = ledgerTransaction(c, depositRef, "DEPOSIT");
          insertEntry(c, deposit, clearing, "DEBIT", "5.00");
          insert(c, "ledger.ledger_entries", entry(high, deposit, account, "CREDIT"));
        });
    assertThat(
            jdbc.queryForObject(
                "SELECT count(DISTINCT created_at) FROM ledger.ledger_entries WHERE id IN (?, ?)",
                Integer.class,
                high,
                low))
        .isEqualTo(1);

    List<Item> pages = new ArrayList<>();
    for (int page = 0; page < 3; page++) {
      MockHttpServletResponse response = history(customer, "?size=1&page=" + page);
      JsonNode info = body(response).get("page");
      assertThat(info.get("totalElements").asLong()).isEqualTo(3);
      assertThat(info.get("totalPages").asLong()).isEqualTo(3);
      assertThat(info.get("page").asInt()).isEqualTo(page);
      assertThat(info.get("size").asInt()).isEqualTo(1);
      pages.addAll(items(response));
    }

    assertThat(pages)
        .containsExactly(
            item(depositRef, "DEPOSIT", "5.00"),
            item(withdrawalRef, "WITHDRAWAL", "5.00"),
            item(depositReference(customer), "DEPOSIT", "100.00"));
    assertThat(items(history(customer, "?size=1&page=3"))).isEmpty();

    // The same page without the index: a sequential scan and an explicit sort by the clause.
    TransactionTemplate withoutIndex = new TransactionTemplate(transactionManager);
    withoutIndex.setReadOnly(true);
    List<String> sorted =
        withoutIndex.execute(
            status -> {
              jdbc.execute("SET LOCAL enable_indexscan = off");
              jdbc.execute("SET LOCAL enable_indexonlyscan = off");
              jdbc.execute("SET LOCAL enable_bitmapscan = off");
              String plan =
                  String.join(
                      "\n",
                      jdbc.queryForList(
                          "EXPLAIN SELECT e.id FROM ledger.ledger_entries e WHERE e.account_id = ?"
                              + " ORDER BY e.created_at DESC, e.id DESC LIMIT 3",
                          String.class,
                          account));
              assertThat(plan).contains("Sort").doesNotContain("Index");
              return historyRows.pageEntries(account, 3, 0).stream()
                  .map(LedgerHistoryRow::reference)
                  .toList();
            });
    assertThat(sorted).containsExactly(depositRef, withdrawalRef, depositReference(customer));
    // The whole history in one page; the planner may pick either plan for it.
    assertThat(allItems(customer)).isEqualTo(pages);
    assertTotalMatchesLedger(customer);
  }

  private static UUID ledgerTransaction(java.sql.Connection c, String reference, String type)
      throws java.sql.SQLException {
    UUID id = UUID.randomUUID();
    insert(
        c,
        "ledger.financial_transactions",
        row(
            "id",
            id,
            "transaction_reference",
            reference,
            "transaction_type",
            type,
            "currency",
            THB));
    return id;
  }

  private static Map<String, Object> entry(
      UUID id, UUID transactionId, UUID accountId, String side) {
    return row(
        "id",
        id,
        "transaction_id",
        transactionId,
        "account_id",
        accountId,
        "entry_type",
        side,
        "amount",
        new BigDecimal("5.00"),
        "currency",
        THB);
  }

  @Test
  void pagingDefaultsAndBounds() throws Exception {
    Account customer = funded("500.00");
    withdraw(customer, "100.00");
    withdraw(customer, "101.00");

    JsonNode defaults = body(history(customer, "")).get("page");
    assertThat(defaults.get("page").asInt()).isZero();
    assertThat(defaults.get("size").asInt()).isEqualTo(20);
    assertThat(defaults.get("totalElements").asLong()).isEqualTo(3);
    assertThat(defaults.get("totalPages").asLong()).isEqualTo(1);
    assertThat(items(history(customer, "?size=2&page=0"))).hasSize(2);
    assertThat(items(history(customer, "?size=2&page=1")))
        .containsExactly(item(depositReference(customer), "DEPOSIT", "500.00"));
    assertThat(items(history(customer, "?size=1"))).hasSize(1);
    assertThat(items(history(customer, "?size=100"))).hasSize(3);
    assertThat(items(history(customer, "?page=7&size=100"))).isEmpty();
    for (String query :
        List.of("?size=0", "?size=101", "?page=-1", "?size=x", "?page=y", "?size=-1")) {
      MockHttpServletResponse refused = history(customer, query);
      assertThat(refused.getStatus()).as(query).isEqualTo(400);
      assertThat(errorCode(refused)).as(query).isEqualTo("VALIDATION_ERROR");
      assertThat(refused.getContentAsString()).as(query).doesNotContain("\"items\"");
    }
  }

  /**
   * A frozen wallet refuses new money movements, not reads: the history is still served, with the
   * same items as before the freeze (owner, 2026-11-14).
   */
  @Test
  void aFrozenWalletStillHasItsHistoryUnchanged() throws Exception {
    Account customer = funded("500.00");
    Account other = funded("300.00");
    withdraw(customer, "100.00");
    transfer(other, customer, "30.00");
    MockHttpServletResponse before = history(customer, "");
    List<Item> itemsBefore = items(before);
    assertThat(itemsBefore).hasSize(3);

    assertThat(freeze(customer).getStatus()).isEqualTo(200);
    assertThat(
            jdbc.queryForObject(
                "SELECT status FROM wallet.wallets WHERE customer_id = ?",
                String.class,
                customer.id()))
        .isEqualTo("FROZEN");
    MockHttpServletResponse after = history(customer, "");

    assertThat(after.getStatus()).isEqualTo(200);
    assertThat(items(after)).isEqualTo(itemsBefore);
    assertThat(body(after).get("page")).isEqualTo(body(before).get("page"));
    assertThat(body(after).get("items")).isEqualTo(body(before).get("items"));
    assertThat(signedSum(items(after))).isEqualByComparingTo(ledgerBalance(customer));
  }

  /** Owner, 2026-11-14: no wallet, no ledger account, so an empty page. */
  @Test
  void beforeKycApprovalTheHistoryIsAnEmptyPage() throws Exception {
    Account customer = customer();

    MockHttpServletResponse response = history(customer, "?page=1&size=5");

    assertThat(items(response)).isEmpty();
    JsonNode page = body(response).get("page");
    assertThat(page.get("page").asInt()).isEqualTo(1);
    assertThat(page.get("size").asInt()).isEqualTo(5);
    assertThat(page.get("totalElements").asLong()).isZero();
    assertThat(page.get("totalPages").asLong()).isZero();
  }

  @ParameterizedTest
  @ValueSource(strings = {"ADMIN", "COMPLIANCE_OFFICER"})
  void staffAreRefused(String role) throws Exception {
    Account staff = account(Role.valueOf(role));

    MockHttpServletResponse refused = history(staff, "");

    assertThat(refused.getStatus()).isEqualTo(403);
    assertThat(refused.getContentAsString()).doesNotContain("\"items\"");
  }

  @Test
  void unauthenticatedIsTheGeneric401() throws Exception {
    MockHttpServletResponse refused = getAs(HISTORY, null);
    MockHttpServletResponse other = getAs("/api/v1/wallet", null);

    assertThat(refused.getStatus()).isEqualTo(401);
    assertThat(stable(refused)).isEqualTo(stable(other));
  }

  /** No {@code GET /transactions/{id}} in the MVP: the generic 404 for any id. */
  @Test
  void thereIsNoDetailEndpoint() throws Exception {
    Account customer = funded("100.00");
    String reference = depositReference(customer);

    MockHttpServletResponse byReference = getAs(HISTORY + "/" + reference, customer.token());
    MockHttpServletResponse byId = getAs(HISTORY + "/" + UUID.randomUUID(), customer.token());

    assertThat(byReference.getStatus()).isEqualTo(404);
    assertThat(stable(byReference)).isEqualTo(stable(byId));
  }

  /** Responses are not cached by the browser (Spring Security's default). */
  @Test
  void responsesAreNotCached() throws Exception {
    MockHttpServletResponse response = history(withWallet(), "");

    assertThat(response.getHeader("Cache-Control")).contains("no-store");
    Matcher fields = Pattern.compile("\"(\\w+)\":").matcher(response.getContentAsString());
    List<String> names = new ArrayList<>();
    while (fields.find()) {
      names.add(fields.group(1));
    }
    assertThat(names).containsOnly("items", "page", "size", "totalElements", "totalPages");
  }
}
