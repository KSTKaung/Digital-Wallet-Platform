package com.portfolio.wallet.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.portfolio.wallet.support.DatabaseIntegrationTest;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Review rows through {@link RiskReviewService} against PostgreSQL (documentations/06 MVP-ADR-24
 * amendment "Review outcomes", D2, D3; V11 checks): every factory's row is accepted by the V11
 * checks, cases 4, 5 and 7 (the PR 5a cases) are stored exactly, a second outcome for a transfer is
 * the distinct {@link ReviewAlreadyRecordedException} and leaves the first row alone, and the
 * service refuses to run without the caller's transaction.
 */
class RiskReviewServiceIT extends DatabaseIntegrationTest {

  @Autowired RiskReviewService reviews;
  @Autowired PlatformTransactionManager transactionManager;

  private TransactionTemplate transaction() {
    return new TransactionTemplate(transactionManager);
  }

  private void recordInTransaction(UUID transfer, UUID assessment, RiskReviewOutcome outcome) {
    transaction().executeWithoutResult(status -> reviews.record(transfer, assessment, outcome));
  }

  private Map<String, Object> reviewOf(UUID transfer) {
    return jdbc.queryForMap(
        "SELECT action, decided_by, requested_action, actor_user_id, reason_category, reason,"
            + " system_reason FROM risk.risk_reviews WHERE transfer_id = ?",
        transfer);
  }

  static Stream<Arguments> everyFactory() {
    return Stream.of(
        Arguments.of(
            "1 staff approval",
            (Function<UUID, RiskReviewOutcome>) RiskReviewOutcome::staffApproval),
        Arguments.of(
            "2 staff rejection",
            (Function<UUID, RiskReviewOutcome>)
                actor -> RiskReviewOutcome.staffRejection(actor, "Purpose could not be confirmed")),
        Arguments.of(
            "3 system rejection",
            (Function<UUID, RiskReviewOutcome>)
                actor ->
                    RiskReviewOutcome.systemRejection(
                        actor, WalletUnavailability.SENDER_WALLET_FROZEN)),
        Arguments.of(
            "4 customer cancel",
            (Function<UUID, RiskReviewOutcome>) RiskReviewOutcome::customerCancel),
        Arguments.of(
            "5 expiry by the job",
            (Function<UUID, RiskReviewOutcome>) actor -> RiskReviewOutcome.expiredByJob()),
        Arguments.of(
            "6 late approval",
            (Function<UUID, RiskReviewOutcome>) RiskReviewOutcome::expiredOnLateApproval),
        Arguments.of(
            "6 late rejection",
            (Function<UUID, RiskReviewOutcome>) RiskReviewOutcome::expiredOnLateRejection),
        Arguments.of(
            "7 late cancel",
            (Function<UUID, RiskReviewOutcome>) RiskReviewOutcome::expiredOnLateCancel));
  }

  /** The domain's seven cases and V11's seven cases agree: every factory's row is stored. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("everyFactory")
  void everyCaseIsAcceptedByTheDatabase(String name, Function<UUID, RiskReviewOutcome> factory)
      throws SQLException {
    UUID assessment = newRiskAssessment();
    UUID transfer = transferOfAssessment(assessment);

    recordInTransaction(transfer, assessment, factory.apply(UUID.randomUUID()));

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM risk.risk_reviews WHERE transfer_id = ?",
                Integer.class,
                transfer))
        .isOne();
  }

  @Test
  void case4CustomerCancelIsStoredExactly() throws SQLException {
    UUID assessment = newRiskAssessment();
    UUID transfer = transferOfAssessment(assessment);
    UUID sender = UUID.randomUUID();

    recordInTransaction(transfer, assessment, RiskReviewOutcome.customerCancel(sender));

    Map<String, Object> row = reviewOf(transfer);
    assertThat(row.get("action")).isEqualTo("CANCEL");
    assertThat(row.get("decided_by")).isEqualTo("CUSTOMER");
    assertThat(row.get("requested_action")).isEqualTo("CANCEL");
    assertThat(row.get("actor_user_id")).isEqualTo(sender);
    assertThat(row.get("reason_category")).isNull();
    assertThat(row.get("reason")).isNull();
    assertThat(row.get("system_reason")).isNull();
  }

  @Test
  void case5ExpiryByTheJobIsStoredExactly() throws SQLException {
    UUID assessment = newRiskAssessment();
    UUID transfer = transferOfAssessment(assessment);

    recordInTransaction(transfer, assessment, RiskReviewOutcome.expiredByJob());

    Map<String, Object> row = reviewOf(transfer);
    assertThat(row.get("action")).isEqualTo("EXPIRE");
    assertThat(row.get("decided_by")).isEqualTo("SYSTEM");
    assertThat(row.get("requested_action")).isNull();
    assertThat(row.get("actor_user_id")).isNull();
    assertThat(row.get("reason_category")).isNull();
    assertThat(row.get("reason")).isNull();
    assertThat(row.get("system_reason")).isEqualTo("REVIEW_DEADLINE_PASSED");
  }

  @Test
  void case7LateCancelIsStoredExactly() throws SQLException {
    UUID assessment = newRiskAssessment();
    UUID transfer = transferOfAssessment(assessment);
    UUID sender = UUID.randomUUID();

    recordInTransaction(transfer, assessment, RiskReviewOutcome.expiredOnLateCancel(sender));

    Map<String, Object> row = reviewOf(transfer);
    assertThat(row.get("action")).isEqualTo("EXPIRE");
    assertThat(row.get("decided_by")).isEqualTo("SYSTEM");
    assertThat(row.get("requested_action")).isEqualTo("CANCEL");
    assertThat(row.get("actor_user_id")).isEqualTo(sender);
    assertThat(row.get("reason_category")).isNull();
    assertThat(row.get("reason")).isNull();
    assertThat(row.get("system_reason")).isEqualTo("REVIEW_DEADLINE_PASSED");
  }

  @Test
  void aSecondOutcomeForTheTransferIsADistinctExceptionAndRollsBack() throws SQLException {
    UUID assessment = newRiskAssessment();
    UUID transfer = transferOfAssessment(assessment);
    UUID sender = UUID.randomUUID();
    recordInTransaction(transfer, assessment, RiskReviewOutcome.customerCancel(sender));

    // The expiry job losing to the cancel, had it not re-read the status under the lock.
    assertThatThrownBy(
            () -> recordInTransaction(transfer, assessment, RiskReviewOutcome.expiredByJob()))
        .isInstanceOf(ReviewAlreadyRecordedException.class);

    assertThat(reviewOf(transfer).get("action")).isEqualTo("CANCEL");
    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM risk.risk_reviews WHERE transfer_id = ?",
                Integer.class,
                transfer))
        .isOne();
  }

  /**
   * A caller that catches the duplicate and returns normally cannot commit: the transaction was
   * marked rollback-only, so the commit throws {@link UnexpectedRollbackException}, and nothing the
   * transaction wrote before the duplicate (here another transfer's review row) is kept.
   */
  @Test
  void aCallerThatSwallowsTheDuplicateGetsAnUnexpectedRollbackAndKeepsNothing()
      throws SQLException {
    UUID assessment = newRiskAssessment();
    UUID transfer = transferOfAssessment(assessment);
    recordInTransaction(transfer, assessment, RiskReviewOutcome.customerCancel(UUID.randomUUID()));
    UUID otherAssessment = newRiskAssessment();
    UUID otherTransfer = transferOfAssessment(otherAssessment);

    assertThatThrownBy(
            () ->
                transaction()
                    .executeWithoutResult(
                        status -> {
                          reviews.record(
                              otherTransfer, otherAssessment, RiskReviewOutcome.expiredByJob());
                          try {
                            reviews.record(transfer, assessment, RiskReviewOutcome.expiredByJob());
                          } catch (ReviewAlreadyRecordedException swallowed) {
                            // A buggy caller: it carries on as if nothing happened.
                          }
                        }))
        .isInstanceOf(UnexpectedRollbackException.class);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM risk.risk_reviews WHERE transfer_id = ?",
                Integer.class,
                otherTransfer))
        .isZero();
    assertThat(reviewOf(transfer).get("action")).isEqualTo("CANCEL");
  }

  @Test
  void recordingOutsideATransactionIsRefusedAndWritesNothing() throws SQLException {
    UUID assessment = newRiskAssessment();
    UUID transfer = transferOfAssessment(assessment);

    assertThatThrownBy(() -> reviews.record(transfer, assessment, RiskReviewOutcome.expiredByJob()))
        .isInstanceOf(IllegalTransactionStateException.class);

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM risk.risk_reviews WHERE transfer_id = ?",
                Integer.class,
                transfer))
        .isZero();
  }

  @Test
  void aReviewMustNameItsAssessmentsTransfer() throws SQLException {
    UUID assessment = newRiskAssessment();

    assertThatThrownBy(
            () ->
                recordInTransaction(
                    UUID.randomUUID(), assessment, RiskReviewOutcome.expiredByJob()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .isNotInstanceOf(ReviewAlreadyRecordedException.class);
  }
}
