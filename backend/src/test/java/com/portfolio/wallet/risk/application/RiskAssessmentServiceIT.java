package com.portfolio.wallet.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.money.Money;
import com.portfolio.wallet.risk.domain.RiskPolicy;
import com.portfolio.wallet.risk.persistence.RiskAssessmentRepository;
import com.portfolio.wallet.support.DatabaseIntegrationTest;
import com.portfolio.wallet.support.PostgresTestDatabase;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The risk assessment against PostgreSQL (documentations/06 MVP-ADR-24; doc 08 §4C.2 Q3 step 6, Q4,
 * Q13; criteria 3, 4): what one committed assessment writes, that a {@code BLOCK} commits with its
 * investigation, that a failure after the first insert leaves nothing, that the per-sender lock
 * serialises one sender's assessments (counts 1..N, none twice) and not other senders', that the
 * preparer runs inside the transaction before the lock, and which assessments the frequency rule
 * counts.
 */
class RiskAssessmentServiceIT extends DatabaseIntegrationTest {

  private static final long WINDOW = 600;
  private static final long TIMEOUT_SECONDS = 30;

  @Autowired RiskAssessmentService service;
  @Autowired RiskAssessmentRepository repository;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired JdbcClient jdbcClient;
  @Autowired com.portfolio.wallet.audit.application.AuditService auditService;

  private ExecutorService pool;

  @BeforeEach
  void setUp() {
    pool = Executors.newFixedThreadPool(8);
  }

  @AfterEach
  void tearDown() {
    pool.shutdownNow();
  }

  // Fixtures ------------------------------------------------------------------------------------

  private static Instant oldWallet() {
    return Instant.now().minus(Duration.ofDays(30));
  }

  private static RiskAssessmentRequest request(
      UUID sender, String amount, Instant walletCreatedAt, boolean knownRecipient) {
    return new RiskAssessmentRequest(
        UUID.randomUUID(), sender, Money.of(amount, Money.THB), walletCreatedAt, knownRecipient);
  }

  /**
   * A policy whose score is the frequency count itself (bands 1..20 weigh 1..20; the other rules
   * never hit for an old wallet, a known recipient and an amount below the single band), so an
   * assessment's score says how many assessments of the sender it counted.
   */
  private RiskAssessmentService countingService(Runnable preparer) {
    RiskPolicy counting =
        new RiskPolicy(
            List.of(new RiskPolicy.AmountBand(Money.of("99999999999999999.99", Money.THB), 1)),
            IntStream.rangeClosed(1, 20).mapToObj(n -> new RiskPolicy.CountBand(n, n)).toList(),
            1,
            List.of(new RiskPolicy.AgeBand(Duration.ofNanos(1000), 1)),
            new RiskPolicy.Thresholds(98, 99, 100));
    return new RiskAssessmentService(
        repository, counting, WINDOW, auditService, transactionManager, preparer);
  }

  private int countFor(RiskAssessmentService counting, UUID sender) {
    return scoreOf(counting.assess(request(sender, "100.00", oldWallet(), true)));
  }

  /** The stored score: the result carries none (staff-only data). */
  private int scoreOf(RiskAssessmentResult result) {
    return jdbc.queryForObject(
        "SELECT score FROM risk.risk_assessments WHERE id = ?",
        Integer.class,
        result.assessmentId());
  }

  private Map<String, Object> assessmentOfTransfer(UUID transferId) {
    return jdbc.queryForMap(
        "SELECT id, customer_id, amount, currency, score, decision, assessed_at"
            + " FROM risk.risk_assessments WHERE transfer_id = ?",
        transferId);
  }

  private int rows(String sql, Object... args) {
    return jdbc.queryForObject(sql, Integer.class, args);
  }

  private Map<String, Integer> hitsOf(UUID assessmentId) {
    Map<String, Integer> hits = new java.util.TreeMap<>();
    jdbc.query(
        "SELECT rule_code, weight FROM risk.risk_rule_hits WHERE assessment_id = ?",
        rs -> {
          hits.put(rs.getString("rule_code"), rs.getInt("weight"));
        },
        assessmentId);
    return hits;
  }

  /** Locks the sender's row from a connection outside the pool; closing it releases the lock. */
  private Connection holdSenderLock(UUID sender) throws SQLException {
    jdbc.update(
        "INSERT INTO risk.sender_risk_state (customer_id) VALUES (?) ON CONFLICT DO NOTHING",
        sender);
    Connection holder = PostgresTestDatabase.openBootstrapSuperuserConnection();
    holder.setAutoCommit(false);
    queryOne(
        holder,
        "SELECT customer_id FROM risk.sender_risk_state WHERE customer_id = ? FOR UPDATE",
        UUID.class,
        sender);
    return holder;
  }

  // What one assessment writes -------------------------------------------------------------------

  @Test
  void anAllowedAssessmentIsCommittedWithItsDatabaseTimeAndNoHits() {
    UUID sender = UUID.randomUUID();
    RiskAssessmentRequest request = request(sender, "100.00", oldWallet(), true);
    OffsetDateTime before = jdbc.queryForObject("SELECT now()", OffsetDateTime.class);

    RiskAssessmentResult result = service.assess(request);

    Map<String, Object> row = assessmentOfTransfer(request.transferId());
    assertThat(row.get("id")).isEqualTo(result.assessmentId());
    assertThat(row.get("customer_id")).isEqualTo(sender);
    assertThat((BigDecimal) row.get("amount")).isEqualByComparingTo("100.00");
    assertThat(row.get("currency")).isEqualTo("THB");
    assertThat(((Number) row.get("score")).intValue()).isZero();
    assertThat(row.get("decision")).isEqualTo("ALLOW");
    assertThat(result.decision()).isEqualTo(AssessmentDecision.ALLOW);
    assertThat(((java.sql.Timestamp) row.get("assessed_at")).toInstant())
        .isAfterOrEqualTo(before.toInstant());
    assertThat(hitsOf(result.assessmentId())).isEmpty();
    assertThat(
            rows(
                "SELECT count(*) FROM risk.investigations WHERE assessment_id = ?",
                result.assessmentId()))
        .isZero();
    assertThat(rows("SELECT count(*) FROM risk.sender_risk_state WHERE customer_id = ?", sender))
        .isOne();
  }

  @Test
  void aBlockCommitsTheAssessmentItsHitsAndAnOpenInvestigationTogether() {
    UUID sender = UUID.randomUUID();
    // Four earlier assessments of this sender: the fifth is +25.
    for (int i = 0; i < 4; i++) {
      service.assess(request(sender, "100.00", oldWallet(), true));
    }
    RiskAssessmentRequest request =
        request(sender, "40000.01", Instant.now().minus(Duration.ofHours(1)), false);

    RiskAssessmentResult result = service.assess(request);

    // 30 + 25 + 15 + 20 = 90.
    assertThat(scoreOf(result)).isEqualTo(90);
    assertThat(result.decision()).isEqualTo(AssessmentDecision.BLOCK);
    assertThat(assessmentOfTransfer(request.transferId()).get("decision")).isEqualTo("BLOCK");
    assertThat(hitsOf(result.assessmentId()))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "TRANSFER_AMOUNT", 30,
                "TRANSFER_FREQUENCY", 25,
                "NEW_RECIPIENT", 15,
                "NEW_SENDER_WALLET", 20));
    Map<String, Object> investigation =
        jdbc.queryForMap(
            "SELECT status, closed_at, closed_by, outcome FROM risk.investigations"
                + " WHERE assessment_id = ?",
            result.assessmentId());
    assertThat(investigation.get("status")).isEqualTo("OPEN");
    assertThat(investigation.get("closed_at")).isNull();
    assertThat(investigation.get("closed_by")).isNull();
    assertThat(investigation.get("outcome")).isNull();
  }

  @Test
  void aManualReviewHasNoInvestigation() {
    UUID sender = UUID.randomUUID();
    for (int i = 0; i < 4; i++) {
      service.assess(request(sender, "100.00", oldWallet(), true));
    }

    // 30 + 25 + 15 = 70.
    RiskAssessmentResult result = service.assess(request(sender, "40000.01", oldWallet(), false));

    assertThat(result.decision()).isEqualTo(AssessmentDecision.MANUAL_REVIEW);
    assertThat(
            rows(
                "SELECT count(*) FROM risk.investigations WHERE assessment_id = ?",
                result.assessmentId()))
        .isZero();
  }

  @Test
  void aSecondAssessmentOfOneTransferIsASystemErrorAndChangesNothing() {
    UUID sender = UUID.randomUUID();
    RiskAssessmentRequest first = request(sender, "100.00", oldWallet(), true);
    service.assess(first);
    RiskAssessmentRequest again =
        new RiskAssessmentRequest(
            first.transferId(), sender, Money.of("200.00", Money.THB), oldWallet(), true);

    assertThatThrownBy(() -> service.assess(again))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SYSTEM_ERROR));
    assertThat(
            rows(
                "SELECT count(*) FROM risk.risk_assessments WHERE transfer_id = ?",
                first.transferId()))
        .isOne();
  }

  // Failure after the first insert (criterion 4)
  // ---------------------------------------------------

  /** A repository whose rule-hit or investigation insert fails after the real insert ran. */
  private RiskAssessmentService failingAfter(String step) {
    RiskAssessmentRepository failing =
        new RiskAssessmentRepository(jdbcClient) {
          @Override
          public void insertRuleHit(UUID assessmentId, String ruleCode, int weight) {
            super.insertRuleHit(assessmentId, ruleCode, weight);
            if (step.equals("hit")) {
              throw new IllegalStateException("injected failure");
            }
          }

          @Override
          public void openInvestigation(UUID assessmentId) {
            super.openInvestigation(assessmentId);
            if (step.equals("investigation")) {
              throw new IllegalStateException("injected failure");
            }
          }
        };
    return new RiskAssessmentService(
        failing,
        com.portfolio.wallet.risk.domain.MvpRiskPolicy.create(),
        WINDOW,
        auditService,
        transactionManager,
        () -> {});
  }

  private void assertNothingStoredFor(UUID sender, RiskAssessmentRequest request) {
    assertThat(
            rows(
                "SELECT count(*) FROM risk.risk_assessments WHERE transfer_id = ?",
                request.transferId()))
        .isZero();
    assertThat(rows("SELECT count(*) FROM risk.risk_assessments WHERE customer_id = ?", sender))
        .isZero();
    assertThat(
            rows(
                "SELECT count(*) FROM risk.risk_rule_hits h JOIN risk.risk_assessments a"
                    + " ON a.id = h.assessment_id WHERE a.customer_id = ?",
                sender))
        .isZero();
    assertThat(
            rows(
                "SELECT count(*) FROM risk.investigations i JOIN risk.risk_assessments a"
                    + " ON a.id = i.assessment_id WHERE a.customer_id = ?",
                sender))
        .isZero();
    // The new lock row rolled back too.
    assertThat(rows("SELECT count(*) FROM risk.sender_risk_state WHERE customer_id = ?", sender))
        .isZero();
  }

  @Test
  void aFailureAfterTheAssessmentInsertLeavesNoAssessmentAndNoHit() {
    UUID sender = UUID.randomUUID();
    RiskAssessmentRequest request = request(sender, "40000.01", oldWallet(), false);

    assertThatThrownBy(() -> failingAfter("hit").assess(request))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SYSTEM_ERROR));

    assertNothingStoredFor(sender, request);
  }

  @Test
  void aFailureAfterTheInvestigationInsertLeavesNoBlockBehind() throws SQLException {
    UUID sender = UUID.randomUUID();
    // Four committed earlier assessments (fixtures), so this one is the fifth: 30 + 25 + 15 + 20 =
    // 90.
    for (int i = 0; i < 4; i++) {
      insert("risk.risk_assessments", with(validRiskAssessment(), "customer_id", sender));
    }
    RiskAssessmentRequest request =
        request(sender, "40000.01", Instant.now().minus(Duration.ofHours(1)), false);

    assertThatThrownBy(() -> failingAfter("investigation").assess(request))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SYSTEM_ERROR));

    assertThat(
            rows(
                "SELECT count(*) FROM risk.risk_assessments WHERE transfer_id = ?",
                request.transferId()))
        .isZero();
    assertThat(
            rows(
                "SELECT count(*) FROM risk.investigations i JOIN risk.risk_assessments a"
                    + " ON a.id = i.assessment_id WHERE a.customer_id = ?",
                sender))
        .isZero();
    assertThat(
            rows(
                "SELECT count(*) FROM risk.risk_rule_hits h JOIN risk.risk_assessments a"
                    + " ON a.id = h.assessment_id WHERE a.customer_id = ?",
                sender))
        .isZero();
    // The same request on a working service is a BLOCK with its investigation.
    assertThat(service.assess(request).decision()).isEqualTo(AssessmentDecision.BLOCK);
  }

  // The per-sender lock -------------------------------------------------------------------------

  @Test
  void concurrentAssessmentsOfOneSenderCountOneToNWithoutDuplicates() throws Exception {
    int n = 8;
    UUID sender = UUID.randomUUID();
    RiskAssessmentService counting = countingService(() -> {});
    List<Future<Integer>> scores = new ArrayList<>();
    try (Connection holder = holdSenderLock(sender)) {
      int holderPid = backendPid(holder);
      for (int i = 0; i < n; i++) {
        scores.add(pool.submit(() -> countFor(counting, sender)));
      }
      // Every assessment really waits on the sender's row before any of them may count.
      awaitBlockedBy(holderPid, n);
      holder.rollback();
    }

    List<Integer> counted = new ArrayList<>();
    for (Future<Integer> score : scores) {
      counted.add(score.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }
    assertThat(counted)
        .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, n).boxed().toList());
    assertThat(
            jdbc.queryForList(
                "SELECT h.weight FROM risk.risk_rule_hits h JOIN risk.risk_assessments a"
                    + " ON a.id = h.assessment_id"
                    + " WHERE a.customer_id = ? AND h.rule_code = 'TRANSFER_FREQUENCY'",
                Integer.class,
                sender))
        .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, n).boxed().toList());
  }

  @Test
  void anotherSendersLockDoesNotBlockAnAssessment() throws Exception {
    UUID locked = UUID.randomUUID();
    UUID other = UUID.randomUUID();
    try (Connection holder = holdSenderLock(locked)) {
      Future<RiskAssessmentResult> result =
          pool.submit(() -> service.assess(request(other, "100.00", oldWallet(), true)));

      assertThat(result.get(TIMEOUT_SECONDS, TimeUnit.SECONDS).decision())
          .isEqualTo(AssessmentDecision.ALLOW);
      assertThat(blockedBy(backendPid(holder))).isZero();
      holder.rollback();
    }
  }

  @Test
  void thePreparerRunsInsideTheTransactionBeforeTheLock() throws Exception {
    UUID sender = UUID.randomUUID();
    // What PR 5a step 4 will do: a lock timeout set in the preparer bounds the sender lock wait.
    RiskAssessmentService bounded =
        countingService(() -> jdbc.execute("SET LOCAL lock_timeout = '200ms'"));
    try (Connection holder = holdSenderLock(sender)) {
      long start = System.nanoTime();

      Future<Integer> attempt = pool.submit(() -> countFor(bounded, sender));

      // Bounded, so a regression (the preparer not applied before the lock) fails instead of
      // hanging.
      assertThatThrownBy(() -> attempt.get(TIMEOUT_SECONDS, TimeUnit.SECONDS))
          .isInstanceOf(java.util.concurrent.ExecutionException.class)
          .cause()
          .isInstanceOfSatisfying(
              ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SYSTEM_ERROR));

      assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(10));
      holder.rollback();
    }
    assertThat(rows("SELECT count(*) FROM risk.risk_assessments WHERE customer_id = ?", sender))
        .isZero();
    // SET LOCAL ended with that transaction: the next assessment is not bounded by it.
    assertThat(jdbc.queryForObject("SHOW lock_timeout", String.class)).isEqualTo("0");
    assertThat(countFor(countingService(() -> {}), sender)).isOne();
  }

  // What the frequency rule counts (Q4)
  // ------------------------------------------------------------

  @Test
  void everyEarlierAssessmentInTheWindowCountsWhateverItsDecisionAndWithoutATransfer()
      throws SQLException {
    UUID sender = UUID.randomUUID();
    // Orphans: no transfer row exists for any of them; one of each decision.
    for (String decision : List.of("ALLOW", "MONITOR", "MANUAL_REVIEW", "BLOCK")) {
      insert(
          "risk.risk_assessments",
          with(with(validRiskAssessment(), "customer_id", sender), "decision", decision));
    }
    // Another sender's assessments never count.
    insert("risk.risk_assessments", validRiskAssessment());

    assertThat(countFor(countingService(() -> {}), sender)).isEqualTo(5);
  }

  @Test
  void onlyAssessmentsInsideTheWindowCount() throws SQLException {
    UUID sender = UUID.randomUUID();
    // Backdated with the timestamp trigger off (test bypass): 9 minutes ago counts, 10 minutes
    // and 1 second ago has left the 10-minute window.
    for (String age : List.of("9 minutes", "10 minutes 1 second", "1 day")) {
      String sql =
          "INSERT INTO risk.risk_assessments"
              + " (transfer_id, customer_id, amount, currency, score, decision, assessed_at)"
              + " VALUES (?, ?, 100.00, 'THB', 0, 'ALLOW', now() - CAST(? AS interval))";
      withTriggersDisabled(c -> update(c, sql, UUID.randomUUID(), sender, age));
    }

    // The 9-minute one and the current one.
    assertThat(countFor(countingService(() -> {}), sender)).isEqualTo(2);
  }

  @Test
  void theWindowIsMeasuredInTheDatabaseAndSeesItsOwnTransactionsNow() {
    UUID sender = UUID.randomUUID();
    UUID transfer = UUID.randomUUID();
    TransactionTemplate template = new TransactionTemplate(transactionManager);

    template.executeWithoutResult(
        status -> {
          repository.lockSender(sender);
          var counted = repository.countInWindow(sender, WINDOW);
          Instant now = jdbc.queryForObject("SELECT now()", OffsetDateTime.class).toInstant();

          assertThat(counted.assessments()).isZero();
          // The count's time is exactly the transaction's now() ...
          assertThat(counted.databaseNow()).isEqualTo(now);
          UUID id =
              repository.insertAssessment(
                  transfer, sender, new BigDecimal("100.00"), "THB", 0, "ALLOW");
          // ... and so is the assessment's own time (V5 trigger).
          assertThat(
                  jdbc.queryForObject(
                          "SELECT assessed_at FROM risk.risk_assessments WHERE id = ?",
                          OffsetDateTime.class,
                          id)
                      .toInstant())
              .isEqualTo(counted.databaseNow());
        });
  }

  /**
   * The exact edge, with {@code now()} fixed by one transaction: a row exactly 600 s before it has
   * left the window, one 599 s before it is inside. The rows are committed from another connection
   * (timestamp trigger off, test bypass) while the transaction is open; read committed lets the
   * count see them.
   */
  @Test
  void theWindowEdgeIsExclusiveAtExactlyTheWindowLength() {
    UUID atEdge = UUID.randomUUID();
    UUID inside = UUID.randomUUID();
    String sql =
        "INSERT INTO risk.risk_assessments"
            + " (transfer_id, customer_id, amount, currency, score, decision, assessed_at)"
            + " VALUES (?, ?, 100.00, 'THB', 0, 'ALLOW', ?)";

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              OffsetDateTime now = jdbc.queryForObject("SELECT now()", OffsetDateTime.class);
              try {
                withTriggersDisabled(
                    c -> {
                      update(c, sql, UUID.randomUUID(), atEdge, now.minusSeconds(WINDOW));
                      update(c, sql, UUID.randomUUID(), inside, now.minusSeconds(WINDOW - 1));
                    });
              } catch (SQLException e) {
                throw new IllegalStateException(e);
              }

              assertThat(repository.countInWindow(atEdge, WINDOW).assessments()).isZero();
              assertThat(repository.countInWindow(inside, WINDOW).assessments()).isOne();
            });
  }

  /**
   * A sender with no {@code sender_risk_state} row yet (DB review M1): N assessments wait together
   * on another transaction's uncommitted insert of that row (the same upsert the service runs), so
   * none of them finds the row when it starts. When it rolls back, the first creates the row and
   * the others wait on it: counts exactly 1..N, and exactly one lock row.
   */
  @Test
  void concurrentFirstAssessmentsOfANewSenderCountOneToNAndCreateOneLockRow() throws Exception {
    int n = 8;
    UUID sender = UUID.randomUUID();
    RiskAssessmentService counting = countingService(() -> {});
    List<Future<Integer>> scores = new ArrayList<>();
    try (Connection holder = PostgresTestDatabase.openBootstrapSuperuserConnection()) {
      holder.setAutoCommit(false);
      update(
          holder,
          "INSERT INTO risk.sender_risk_state (customer_id) VALUES (?)"
              + " ON CONFLICT (customer_id) DO UPDATE SET updated_at = now()",
          sender);
      int holderPid = backendPid(holder);
      for (int i = 0; i < n; i++) {
        scores.add(pool.submit(() -> countFor(counting, sender)));
      }
      awaitBlockedBy(holderPid, n);
      // No row is left behind: the assessments start from a sender without one.
      holder.rollback();
    }

    List<Integer> counted = new ArrayList<>();
    for (Future<Integer> score : scores) {
      counted.add(score.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }
    assertThat(counted)
        .containsExactlyInAnyOrderElementsOf(IntStream.rangeClosed(1, n).boxed().toList());
    assertThat(rows("SELECT count(*) FROM risk.sender_risk_state WHERE customer_id = ?", sender))
        .isOne();
  }

  // Audit of the risk decision (MVP-ADR-24 "Audit of risk decisions", owner 2026-11-09) ----------

  @Autowired com.portfolio.wallet.audit.persistence.AuditLogRepository auditLogs;
  @Autowired tools.jackson.databind.json.JsonMapper jsonMapper;

  private List<Map<String, Object>> auditRowsOf(UUID assessmentId) {
    return jdbc.queryForList(
        "SELECT actor_id, actor_type, action, result, client_ip, old_value::text AS old_value,"
            + " new_value::text AS new_value, occurred_at FROM audit.audit_logs"
            + " WHERE resource = 'RISK_ASSESSMENT' AND resource_id = ?",
        assessmentId.toString());
  }

  private int auditRowsOfSender(UUID sender) {
    return rows(
        "SELECT count(*) FROM audit.audit_logs WHERE resource = 'RISK_ASSESSMENT' AND actor_id = ?",
        sender);
  }

  @Test
  void eachDecisionCommitsExactlyOneAuditRowWithItsAssessment() {
    UUID sender = UUID.randomUUID();
    Instant hourOld = Instant.now().minus(Duration.ofHours(1));
    Instant twoDaysOld = Instant.now().minus(Duration.ofDays(2));
    Map<AssessmentDecision, RiskAssessmentResult> byDecision =
        new java.util.EnumMap<>(AssessmentDecision.class);
    // 1st: 0 ALLOW; 2nd: 30 + 10 = 40 MONITOR; 3rd, 4th: ALLOW (3 and 4 transfers: +15).
    for (RiskAssessmentRequest request :
        List.of(
            request(sender, "100.00", oldWallet(), true),
            request(sender, "40000.01", twoDaysOld, true),
            request(sender, "100.00", oldWallet(), true),
            request(sender, "100.00", oldWallet(), true),
            // 5th: 30 + 25 + 15 = 70 MANUAL_REVIEW; 6th: 30 + 25 + 15 + 20 = 90 BLOCK.
            request(sender, "40000.01", oldWallet(), false),
            request(sender, "40000.01", hourOld, false))) {
      RiskAssessmentResult result = service.assess(request);
      byDecision.putIfAbsent(result.decision(), result);
      assertThat(auditRowsOf(result.assessmentId())).hasSize(1);
    }
    assertThat(byDecision).containsOnlyKeys(AssessmentDecision.values());
    assertThat(auditRowsOfSender(sender)).isEqualTo(6);

    for (Map.Entry<AssessmentDecision, RiskAssessmentResult> e : byDecision.entrySet()) {
      UUID assessmentId = e.getValue().assessmentId();
      Map<String, Object> row = auditRowsOf(assessmentId).getFirst();
      assertThat(row.get("actor_id")).isEqualTo(sender);
      assertThat(row.get("actor_type")).isEqualTo("CUSTOMER");
      assertThat(row.get("action")).isEqualTo("RISK_DECISION");
      assertThat(row.get("result")).isEqualTo("SUCCESS");
      assertThat(row.get("client_ip")).isNull();
      assertThat(row.get("old_value")).isNull();
      @SuppressWarnings("unchecked")
      Map<String, Object> details = jsonMapper.readValue((String) row.get("new_value"), Map.class);
      boolean block = e.getKey() == AssessmentDecision.BLOCK;
      assertThat(details)
          .containsExactlyInAnyOrderEntriesOf(
              Map.of("decision", e.getKey().name(), "investigationOpened", block));
      // Committed together: the audit row and the assessment share the transaction's now().
      assertThat(row.get("occurred_at"))
          .isEqualTo(
              jdbc.queryForObject(
                  "SELECT assessed_at FROM risk.risk_assessments WHERE id = ?",
                  java.sql.Timestamp.class,
                  assessmentId));
      // Never the amount, the score, a rule code or a weight.
      String text = (String) row.get("new_value");
      int score =
          jdbc.queryForObject(
              "SELECT score FROM risk.risk_assessments WHERE id = ?", Integer.class, assessmentId);
      assertThat(text)
          .doesNotContain("40000")
          .doesNotContain("100.00")
          .doesNotContain("score")
          .doesNotContain("weight")
          .doesNotContain("TRANSFER_")
          .doesNotContain("NEW_");
      if (score > 0) {
        assertThat(text).doesNotContain(Integer.toString(score));
      }
    }
  }

  @Test
  void aFailureAfterTheAuditInsertLeavesNoAuditRowAndNoAssessment() {
    UUID sender = UUID.randomUUID();
    com.portfolio.wallet.audit.application.AuditService failingAudit =
        new com.portfolio.wallet.audit.application.AuditService(auditLogs, jsonMapper) {
          @Override
          public void record(com.portfolio.wallet.audit.application.AuditEntry entry) {
            super.record(entry);
            throw new IllegalStateException("injected failure after the audit insert");
          }
        };
    RiskAssessmentService broken =
        new RiskAssessmentService(
            repository,
            com.portfolio.wallet.risk.domain.MvpRiskPolicy.create(),
            WINDOW,
            failingAudit,
            transactionManager,
            () -> {});
    RiskAssessmentRequest request =
        request(sender, "40000.01", Instant.now().minus(Duration.ofHours(1)), false);

    assertThatThrownBy(() -> broken.assess(request))
        .isInstanceOfSatisfying(
            ApiException.class, e -> assertThat(e.errorCode()).isEqualTo(ErrorCode.SYSTEM_ERROR));

    assertNothingStoredFor(sender, request);
    assertThat(auditRowsOfSender(sender)).isZero();
  }

  @Test
  void aFailedAssessmentWritesNoRiskAuditRow() {
    UUID sender = UUID.randomUUID();

    assertThatThrownBy(
            () -> failingAfter("hit").assess(request(sender, "40000.01", oldWallet(), false)))
        .isInstanceOf(ApiException.class);

    // The transfer module audits the failure on its own (step 5); nothing here survives.
    assertThat(auditRowsOfSender(sender)).isZero();
  }
}
