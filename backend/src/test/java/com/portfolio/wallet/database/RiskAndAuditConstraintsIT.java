package com.portfolio.wallet.database;

import static com.portfolio.wallet.support.SqlAssertions.NOT_NULL_VIOLATION;
import static com.portfolio.wallet.support.SqlAssertions.assertCheckViolation;
import static com.portfolio.wallet.support.SqlAssertions.assertForeignKeyViolation;
import static com.portfolio.wallet.support.SqlAssertions.assertRestrictViolation;
import static com.portfolio.wallet.support.SqlAssertions.assertSqlState;
import static com.portfolio.wallet.support.SqlAssertions.assertUniqueViolation;
import static org.assertj.core.api.Assertions.assertThat;

import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Risk and audit constraints (documentations/06 MVP-ADR-02, -24; doc 03 §7.2; acceptance criterion
 * 7, test case 7). Every statement runs as {@code wallet_app}.
 */
class RiskAndAuditConstraintsIT extends DatabaseIntegrationTest {

  // Risk assessments (criterion 7) ------------------------------------------------------------

  @ParameterizedTest(name = "score {0}")
  @ValueSource(ints = {0, 100})
  void scoreBoundariesAreAccepted(int score) throws SQLException {
    Map<String, Object> assessment = with(validRiskAssessment(), "score", score);

    insert("risk.risk_assessments", assessment);

    assertThat(
            jdbc.queryForObject(
                "SELECT score FROM risk.risk_assessments WHERE id = ?",
                Integer.class,
                assessment.get("id")))
        .isEqualTo(score);
  }

  @ParameterizedTest(name = "score {0}")
  @ValueSource(ints = {-1, 101})
  void scoreOutsideZeroToHundredIsRejected(int score) {
    assertCheckViolation(
        "ck_risk_assessments_score",
        () -> insert("risk.risk_assessments", with(validRiskAssessment(), "score", score)));
  }

  static Stream<Arguments> invalidAssessmentColumns() {
    return Stream.of(
        Arguments.of("amount", new BigDecimal("0.00"), "ck_risk_assessments_amount"),
        Arguments.of("currency", "USD", "ck_risk_assessments_currency"),
        Arguments.of("decision", "HIGH", "ck_risk_assessments_decision"));
  }

  @ParameterizedTest(name = "{0} = {1}")
  @MethodSource("invalidAssessmentColumns")
  void assessmentChecksRejectInvalidValues(String column, Object value, String constraint) {
    assertCheckViolation(
        constraint,
        () -> insert("risk.risk_assessments", with(validRiskAssessment(), column, value)));
  }

  @Test
  void oneAssessmentPerTransfer() throws SQLException {
    Map<String, Object> first = validRiskAssessment();
    insert("risk.risk_assessments", first);

    assertUniqueViolation(
        "uq_risk_assessments_transfer",
        () ->
            insert(
                "risk.risk_assessments",
                with(validRiskAssessment(), "transfer_id", first.get("transfer_id"))));
  }

  @ParameterizedTest(name = "weight {0}")
  @ValueSource(ints = {0, 101})
  void ruleHitWeightOutsideOneToHundredIsRejected(int weight) throws SQLException {
    UUID assessment = newRiskAssessment();

    assertCheckViolation(
        "ck_risk_rule_hits_weight",
        () ->
            insert(
                "risk.risk_rule_hits",
                row(
                    "assessment_id",
                    assessment,
                    "rule_code",
                    "TRANSFER_AMOUNT",
                    "weight",
                    weight)));
  }

  // Rule codes (V12; MVP-ADR-24 "Score") -----------------------------------------------------

  /** Every MVP rule code is accepted, one hit per rule on the same assessment. */
  @Test
  void theFourRuleCodesAreAccepted() throws SQLException {
    UUID assessment = newRiskAssessment();
    for (String code :
        new String[] {
          "TRANSFER_AMOUNT", "TRANSFER_FREQUENCY", "NEW_RECIPIENT", "NEW_SENDER_WALLET"
        }) {
      insert(
          "risk.risk_rule_hits", row("assessment_id", assessment, "rule_code", code, "weight", 10));
    }

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM risk.risk_rule_hits WHERE assessment_id = ?",
                Integer.class,
                assessment))
        .isEqualTo(4);
  }

  /** Anything else is refused: an old fixture code, another spelling, a blank. */
  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(
      strings = {
        "LARGE_AMOUNT",
        "transfer_amount",
        "TRANSFER_AMOUNT ",
        "NEW_WALLET",
        "VELOCITY",
        ""
      })
  void otherRuleCodesAreRejected(String code) throws SQLException {
    UUID assessment = newRiskAssessment();

    assertCheckViolation(
        "ck_risk_rule_hits_rule_code",
        () ->
            insert(
                "risk.risk_rule_hits",
                row("assessment_id", assessment, "rule_code", code, "weight", 10)));
  }

  @Test
  void aRuleHitNeedsARuleCode() throws SQLException {
    UUID assessment = newRiskAssessment();

    assertSqlState(
        NOT_NULL_VIOLATION,
        () ->
            insert(
                "risk.risk_rule_hits",
                row("assessment_id", assessment, "rule_code", null, "weight", 10)));
  }

  @Test
  void aRuleHitTwiceOnOneAssessmentIsRejected() throws SQLException {
    UUID assessment = newRiskAssessment();
    insert(
        "risk.risk_rule_hits",
        row("assessment_id", assessment, "rule_code", "NEW_RECIPIENT", "weight", 15));

    assertUniqueViolation(
        "pk_risk_rule_hits",
        () ->
            insert(
                "risk.risk_rule_hits",
                row("assessment_id", assessment, "rule_code", "NEW_RECIPIENT", "weight", 20)));
  }

  // Risk reviews ------------------------------------------------------------------------------

  private Map<String, Object> review(UUID assessment, String action, UUID actor) {
    return validRiskReview(assessment, action, actor);
  }

  @Test
  void reviewMustNameItsAssessmentsTransfer() throws SQLException {
    UUID assessment = newRiskAssessment();

    assertForeignKeyViolation(
        "fk_risk_reviews_assessment",
        () ->
            insert(
                "risk.risk_reviews",
                with(review(assessment, "EXPIRE", null), "transfer_id", UUID.randomUUID())));
  }

  @Test
  void aTransferIsReviewedOnce() throws SQLException {
    UUID assessment = newRiskAssessment();
    insert("risk.risk_reviews", review(assessment, "APPROVE", UUID.randomUUID()));

    assertUniqueViolation(
        "uq_risk_reviews_transfer",
        () -> insert("risk.risk_reviews", review(assessment, "EXPIRE", null)));
  }

  @Test
  void clientSuppliedAssessedAtIsOverwritten() throws SQLException {
    Map<String, Object> assessment = with(validRiskAssessment(), "assessed_at", BACKDATED);
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          now[0] = transactionNow(c);
          insert(c, "risk.risk_assessments", assessment);
        });

    assertThat(
            jdbc.queryForObject(
                "SELECT assessed_at FROM risk.risk_assessments WHERE id = ?",
                OffsetDateTime.class,
                assessment.get("id")))
        .isEqualTo(now[0]);
  }

  @Test
  void clientSuppliedReviewCreatedAtIsOverwritten() throws SQLException {
    Map<String, Object> review =
        with(review(newRiskAssessment(), "EXPIRE", null), "created_at", BACKDATED);
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          now[0] = transactionNow(c);
          insert(c, "risk.risk_reviews", review);
        });

    assertThat(
            jdbc.queryForObject(
                "SELECT created_at FROM risk.risk_reviews WHERE id = ?",
                OffsetDateTime.class,
                review.get("id")))
        .isEqualTo(now[0]);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"APPROVE", "REJECT", "CANCEL"})
  void staffReviewWithoutActorIsRejected(String action) throws SQLException {
    UUID assessment = newRiskAssessment();

    assertCheckViolation(
        "ck_risk_reviews_actor",
        () -> insert("risk.risk_reviews", review(assessment, action, null)));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"APPROVE", "REJECT", "CANCEL"})
  void staffReviewWithActorIsAccepted(String action) throws SQLException {
    insert("risk.risk_reviews", review(newRiskAssessment(), action, UUID.randomUUID()));
  }

  @Test
  void expiryByTheSchedulerNeedsNoActor() throws SQLException {
    insert("risk.risk_reviews", review(newRiskAssessment(), "EXPIRE", null));
  }

  @Test
  void unknownReviewActionIsRejected() throws SQLException {
    UUID assessment = newRiskAssessment();

    assertCheckViolation(
        "ck_risk_reviews_action",
        () -> insert("risk.risk_reviews", review(assessment, "ESCALATE", UUID.randomUUID())));
  }

  // Review outcomes (V11; owner, 2026-11-06, D2 and D3) ---------------------------------------

  private static final UUID SOMEONE = UUID.fromString("00000000-0000-4000-8000-000000000001");

  /** One review row of each case of the D2 table (doc 06 MVP-ADR-24 "Review outcomes"). */
  private Map<String, Object> reviewCase(String reviewCase) throws SQLException {
    UUID assessment = newRiskAssessment();
    return switch (reviewCase) {
      case "1 staff approval" -> validRiskReview(assessment, "APPROVE", SOMEONE);
      case "2 staff rejection" -> validRiskReview(assessment, "REJECT", SOMEONE);
      case "3 system rejection" -> {
        Map<String, Object> review = validRiskReview(assessment, "REJECT", SOMEONE);
        review.put("decided_by", "SYSTEM");
        review.put("requested_action", "APPROVE");
        review.put("reason_category", null);
        review.put("reason", null);
        review.put("system_reason", "SENDER_WALLET_FROZEN");
        yield review;
      }
      case "4 customer cancel" -> validRiskReview(assessment, "CANCEL", SOMEONE);
      case "5 job expiry" -> validRiskReview(assessment, "EXPIRE", null);
      case "6 late staff action" -> validRiskReview(assessment, "EXPIRE", SOMEONE);
      case "7 late customer cancel" ->
          with(validRiskReview(assessment, "EXPIRE", SOMEONE), "requested_action", "CANCEL");
      default -> throw new IllegalArgumentException(reviewCase);
    };
  }

  private Map<String, Object> systemRejection(String systemReason) throws SQLException {
    return with(reviewCase("3 system rejection"), "system_reason", systemReason);
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "1 staff approval",
        "2 staff rejection",
        "3 system rejection",
        "4 customer cancel",
        "5 job expiry",
        "6 late staff action",
        "7 late customer cancel"
      })
  void everyReviewCaseIsAccepted(String reviewCase) throws SQLException {
    insert("risk.risk_reviews", reviewCase(reviewCase));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(
      strings = {
        "SENDER_WALLET_FROZEN",
        "SENDER_WALLET_CLOSED",
        "RECIPIENT_WALLET_FROZEN",
        "RECIPIENT_WALLET_CLOSED"
      })
  void aSystemRejectionNamesTheUnavailableWallet(String systemReason) throws SQLException {
    insert("risk.risk_reviews", systemRejection(systemReason));
  }

  /**
   * Rows outside the D2 table, each refused by the check named. PostgreSQL applies CHECK
   * constraints in name order, so the first rule a row breaks is the one reported.
   */
  static Stream<Arguments> reviewsOutsideThePolicy() {
    return Stream.of(
        // Staff and system rejections never mix.
        Arguments.of(
            "2 staff rejection",
            "system_reason",
            "SENDER_WALLET_FROZEN",
            "ck_risk_reviews_system_decision"),
        Arguments.of("2 staff rejection", "reason", null, "ck_risk_reviews_staff_rejection"),
        Arguments.of(
            "2 staff rejection", "reason_category", null, "ck_risk_reviews_staff_rejection"),
        Arguments.of(
            "2 staff rejection",
            "reason_category",
            "REVIEW_DECLINED",
            "ck_risk_reviews_reason_category"),
        Arguments.of(
            "2 staff rejection",
            "reason_category",
            "SUSPECTED_FRAUD",
            "ck_risk_reviews_reason_category"),
        Arguments.of("2 staff rejection", "reason", "   ", "ck_risk_reviews_reason_text"),
        Arguments.of("2 staff rejection", "requested_action", "APPROVE", "ck_risk_reviews_outcome"),
        Arguments.of("2 staff rejection", "actor_user_id", null, "ck_risk_reviews_actor"),
        Arguments.of(
            "3 system rejection", "reason", "Reviewer text", "ck_risk_reviews_staff_rejection"),
        Arguments.of(
            "3 system rejection",
            "reason_category",
            "STAFF_REJECTION",
            "ck_risk_reviews_staff_rejection"),
        Arguments.of(
            "3 system rejection",
            "system_reason",
            "REVIEW_DEADLINE_PASSED",
            "ck_risk_reviews_outcome"),
        Arguments.of("3 system rejection", "system_reason", null, "ck_risk_reviews_outcome"),
        Arguments.of("3 system rejection", "requested_action", "REJECT", "ck_risk_reviews_outcome"),
        Arguments.of("3 system rejection", "requested_action", null, "ck_risk_reviews_outcome"),
        // Approvals and cancels are not system decisions and carry no reason.
        Arguments.of(
            "1 staff approval",
            "system_reason",
            "REVIEW_DEADLINE_PASSED",
            "ck_risk_reviews_system_decision"),
        Arguments.of("1 staff approval", "decided_by", "SYSTEM", "ck_risk_reviews_outcome"),
        Arguments.of("1 staff approval", "requested_action", null, "ck_risk_reviews_outcome"),
        Arguments.of("1 staff approval", "reason", "Looks fine", "ck_risk_reviews_staff_rejection"),
        Arguments.of("4 customer cancel", "decided_by", "STAFF", "ck_risk_reviews_outcome"),
        Arguments.of("4 customer cancel", "requested_action", null, "ck_risk_reviews_outcome"),
        Arguments.of(
            "4 customer cancel", "reason", "Changed my mind", "ck_risk_reviews_staff_rejection"),
        // Expiry is always the system's, for the deadline, with an actor exactly after a request.
        Arguments.of("5 job expiry", "actor_user_id", SOMEONE, "ck_risk_reviews_outcome"),
        Arguments.of("5 job expiry", "decided_by", "STAFF", "ck_risk_reviews_outcome"),
        Arguments.of(
            "5 job expiry", "system_reason", "SENDER_WALLET_FROZEN", "ck_risk_reviews_outcome"),
        Arguments.of("5 job expiry", "system_reason", null, "ck_risk_reviews_outcome"),
        Arguments.of("6 late staff action", "requested_action", null, "ck_risk_reviews_outcome"),
        Arguments.of(
            "6 late staff action",
            "requested_action",
            "EXPIRE",
            "ck_risk_reviews_requested_action"),
        Arguments.of("6 late staff action", "system_reason", "OTHER", "ck_risk_reviews_outcome"),
        Arguments.of("6 late staff action", "decided_by", "ADMIN", "ck_risk_reviews_decided_by"),
        // A late action stores no staff reason: the reviewer's text and category are dropped.
        Arguments.of(
            "6 late staff action", "reason", "Reviewer text", "ck_risk_reviews_staff_rejection"),
        Arguments.of(
            "6 late staff action",
            "reason_category",
            "STAFF_REJECTION",
            "ck_risk_reviews_staff_rejection"),
        Arguments.of("5 job expiry", "reason", "Reviewer text", "ck_risk_reviews_staff_rejection"),
        // A requested action always has its triggering actor.
        Arguments.of("6 late staff action", "actor_user_id", null, "ck_risk_reviews_outcome"),
        Arguments.of("7 late customer cancel", "actor_user_id", null, "ck_risk_reviews_outcome"),
        Arguments.of("7 late customer cancel", "decided_by", "CUSTOMER", "ck_risk_reviews_outcome"),
        Arguments.of("3 system rejection", "actor_user_id", null, "ck_risk_reviews_actor"),
        // Only listed system reasons, and only on system decisions.
        Arguments.of(
            "3 system rejection", "system_reason", "OTHER", "ck_risk_reviews_system_reason"),
        Arguments.of(
            "4 customer cancel",
            "system_reason",
            "SENDER_WALLET_FROZEN",
            "ck_risk_reviews_system_decision"),
        // A staff category alone, without a staff rejection, is refused too.
        Arguments.of(
            "1 staff approval",
            "reason_category",
            "STAFF_REJECTION",
            "ck_risk_reviews_staff_rejection"),
        Arguments.of(
            "4 customer cancel",
            "reason_category",
            "STAFF_REJECTION",
            "ck_risk_reviews_staff_rejection"));
  }

  /** Row 6 covers a late rejection as well as a late approval (doc 06 "Review outcomes"). */
  @Test
  void aLateRejectionExpiresWithoutTheReviewersReason() throws SQLException {
    insert(
        "risk.risk_reviews", with(reviewCase("6 late staff action"), "requested_action", "REJECT"));
  }

  @ParameterizedTest(name = "{0}: {1} = {2}")
  @MethodSource("reviewsOutsideThePolicy")
  void aReviewOutsideThePolicyIsRefused(
      String reviewCase, String column, Object value, String constraint) throws SQLException {
    Map<String, Object> review = with(reviewCase(reviewCase), column, value);

    assertCheckViolation(constraint, () -> insert("risk.risk_reviews", review));
  }

  @Test
  void everyReviewNamesItsDecisionSource() throws SQLException {
    Map<String, Object> review = with(reviewCase("1 staff approval"), "decided_by", null);

    assertSqlState(NOT_NULL_VIOLATION, () -> insert("risk.risk_reviews", review));
  }

  // Investigations ----------------------------------------------------------------------------

  @Test
  void clientSuppliedOpenedAtIsOverwritten() throws SQLException {
    UUID id = UUID.randomUUID();
    UUID assessment = newRiskAssessment();
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          now[0] = transactionNow(c);
          insert(
              c,
              "risk.investigations",
              row("id", id, "assessment_id", assessment, "opened_at", BACKDATED));
        });

    assertThat(
            jdbc.queryForObject(
                "SELECT opened_at FROM risk.investigations WHERE id = ?", OffsetDateTime.class, id))
        .isEqualTo(now[0]);
  }

  static Stream<Arguments> immutableInvestigationColumns() {
    return Stream.of(
        Arguments.of("id", UUID.randomUUID()),
        Arguments.of("assessment_id", UUID.randomUUID()),
        Arguments.of("opened_at", BACKDATED));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("immutableInvestigationColumns")
  void openInvestigationCannotMoveItsIdentity(String column, Object value) throws SQLException {
    UUID id = UUID.randomUUID();
    insert("risk.investigations", row("id", id, "assessment_id", newRiskAssessment()));

    assertRestrictViolation(
        "id, assessment_id and opened_at are immutable",
        () ->
            jdbc.update(
                "UPDATE risk.investigations SET " + column + " = ? WHERE id = ?", value, id));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"CLOSED", "ESCALATED"})
  void investigationCanOnlyBeInsertedAsOpen(String status) throws SQLException {
    UUID assessment = newRiskAssessment();

    assertRestrictViolation(
        "a new investigation must be OPEN",
        () ->
            insert(
                "risk.investigations",
                row(
                    "assessment_id",
                    assessment,
                    "status",
                    status,
                    "closed_at",
                    OffsetDateTime.now(),
                    "closed_by",
                    UUID.randomUUID(),
                    "outcome",
                    "no fraud")));
  }

  @Test
  void closedInvestigationWithoutClosingTimeFailsTheCheckOnceTheTriggerIsOutOfTheWay()
      throws SQLException {
    UUID assessment = newRiskAssessment();

    assertCheckViolation(
        "ck_investigations_closed_by_outcome",
        () ->
            insertWithTriggersDisabled(
                "risk.investigations",
                row(
                    "assessment_id",
                    assessment,
                    "status",
                    "CLOSED",
                    "closed_by",
                    UUID.randomUUID(),
                    "outcome",
                    "no fraud")));
  }

  static Stream<Arguments> closingFields() {
    return Stream.of(
        Arguments.of("closed_at", OffsetDateTime.now(ZoneOffset.UTC)),
        Arguments.of("closed_by", UUID.randomUUID()),
        Arguments.of("outcome", "no fraud"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("closingFields")
  void openInvestigationCannotBeInsertedWithAClosingField(String column, Object value)
      throws SQLException {
    UUID assessment = newRiskAssessment();

    assertCheckViolation(
        "ck_investigations_closed",
        () -> insert("risk.investigations", row("assessment_id", assessment, column, value)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("closingFields")
  void openInvestigationCannotBeUpdatedWithAClosingField(String column, Object value)
      throws SQLException {
    UUID id = UUID.randomUUID();
    insert("risk.investigations", row("id", id, "assessment_id", newRiskAssessment()));

    assertCheckViolation(
        "ck_investigations_closed",
        () ->
            jdbc.update(
                "UPDATE risk.investigations SET " + column + " = ? WHERE id = ?", value, id));
  }

  static Stream<Arguments> closingWithoutCloserOrOutcome() {
    return Stream.of(
        Arguments.of("closed_by = NULL, outcome = 'no fraud'"),
        Arguments.of("closed_by = gen_random_uuid(), outcome = NULL"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("closingWithoutCloserOrOutcome")
  void closingAnInvestigationRequiresCloserAndOutcome(String assignments) throws SQLException {
    UUID id = UUID.randomUUID();
    insert("risk.investigations", row("id", id, "assessment_id", newRiskAssessment()));

    assertCheckViolation(
        "ck_investigations_closed_by_outcome",
        () ->
            jdbc.update(
                "UPDATE risk.investigations SET status = 'CLOSED', "
                    + assignments
                    + " WHERE id = ?",
                id));
  }

  @Test
  void closedInvestigationCannotChangeAgain() throws SQLException {
    UUID id = UUID.randomUUID();
    insert("risk.investigations", row("id", id, "assessment_id", newRiskAssessment()));
    jdbc.update(
        "UPDATE risk.investigations SET status = 'CLOSED',"
            + " closed_by = ?, outcome = 'no fraud' WHERE id = ?",
        UUID.randomUUID(),
        id);

    assertRestrictViolation(
        "is CLOSED and can no longer change",
        () -> jdbc.update("UPDATE risk.investigations SET outcome = 'changed' WHERE id = ?", id));
    assertRestrictViolation(
        "is CLOSED and can no longer change",
        () ->
            jdbc.update(
                "UPDATE risk.investigations SET status = 'OPEN', closed_at = NULL WHERE id = ?",
                id));
  }

  @Test
  void closingAnInvestigationSetsTheServerTime() throws SQLException {
    assertClosedAtIsTheUpdateStatementsTime("");
  }

  @Test
  void clientSuppliedClosedAtIsOverwritten() throws SQLException {
    assertClosedAtIsTheUpdateStatementsTime(", closed_at = '2001-01-01T00:00:00Z'");
  }

  private void assertClosedAtIsTheUpdateStatementsTime(String extraAssignment) throws SQLException {
    UUID id = UUID.randomUUID();
    insert("risk.investigations", row("id", id, "assessment_id", newRiskAssessment()));

    try (Connection c = openTransaction()) {
      OffsetDateTime transactionStart = transactionNow(c);
      queryOne(c, "SELECT 1 FROM pg_sleep(0.05)", Integer.class);

      assertThat(
              update(
                  c,
                  "UPDATE risk.investigations SET status = 'CLOSED', closed_by = ?,"
                      + " outcome = 'no fraud'"
                      + extraAssignment
                      + " WHERE id = ?",
                  UUID.randomUUID(),
                  id))
          .isEqualTo(1);

      // After the transaction's now(), so the statement's time; not the client's value.
      assertThat(
              queryOne(
                  c,
                  "SELECT closed_at FROM risk.investigations WHERE id = ?",
                  OffsetDateTime.class,
                  id))
          .isAfter(transactionStart);
    }
  }

  @Test
  void oneInvestigationPerAssessment() throws SQLException {
    UUID assessment = newRiskAssessment();
    insert("risk.investigations", row("assessment_id", assessment));

    assertUniqueViolation(
        "uq_investigations_assessment",
        () -> insert("risk.investigations", row("assessment_id", assessment)));
  }

  @Test
  void unknownInvestigationStatusIsRejected() throws SQLException {
    UUID id = UUID.randomUUID();
    insert("risk.investigations", row("id", id, "assessment_id", newRiskAssessment()));

    assertCheckViolation(
        "ck_investigations_status",
        () ->
            jdbc.update(
                "UPDATE risk.investigations SET status = 'ESCALATED', closed_at = now(),"
                    + " closed_by = ?, outcome = 'no fraud' WHERE id = ?",
                UUID.randomUUID(),
                id));
  }

  // Audit log ---------------------------------------------------------------------------------

  private static Map<String, Object> validAuditRow() {
    return row(
        "actor_type", "SYSTEM",
        "action", "TEST",
        "resource", "TEST",
        "result", "SUCCESS",
        "correlation_id", UUID.randomUUID().toString());
  }

  static Stream<Arguments> invalidAuditColumns() {
    return Stream.of(
        Arguments.of("actor_type", "ROBOT", "ck_audit_logs_actor_type"),
        Arguments.of("result", "MAYBE", "ck_audit_logs_result"));
  }

  @ParameterizedTest(name = "{0} = {1}")
  @MethodSource("invalidAuditColumns")
  void auditChecksRejectInvalidValues(String column, Object value, String constraint) {
    assertCheckViolation(
        constraint, () -> insert("audit.audit_logs", with(validAuditRow(), column, value)));
  }

  @Test
  void clientSuppliedOccurredAtIsOverwritten() throws SQLException {
    Map<String, Object> audit =
        with(
            validAuditRow(),
            "occurred_at",
            OffsetDateTime.of(2001, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    OffsetDateTime[] now = new OffsetDateTime[1];

    inTransaction(
        c -> {
          now[0] = transactionNow(c);
          insert(c, "audit.audit_logs", audit);
        });

    assertThat(
            jdbc.queryForObject(
                "SELECT occurred_at FROM audit.audit_logs WHERE correlation_id = ?",
                OffsetDateTime.class,
                audit.get("correlation_id")))
        .isEqualTo(now[0]);
  }
}
