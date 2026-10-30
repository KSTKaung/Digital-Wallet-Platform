package com.portfolio.wallet.database;

import static com.portfolio.wallet.support.SqlAssertions.assertCheckViolation;
import static com.portfolio.wallet.support.SqlAssertions.assertForeignKeyViolation;
import static com.portfolio.wallet.support.SqlAssertions.assertRestrictViolation;
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
                row("assessment_id", assessment, "rule_code", "LARGE_AMOUNT", "weight", weight)));
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
