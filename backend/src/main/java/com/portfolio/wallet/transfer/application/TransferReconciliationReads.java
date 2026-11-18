package com.portfolio.wallet.transfer.application;

import com.portfolio.wallet.transfer.persistence.TransferReconciliationRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * What the scheduled reconciliation reads from the transfer module (documentations/06 MVP-ADR-03
 * checks 2–5, MVP-ADR-22 Y5, MVP-ADR-24 "Review outcomes"; doc 07 §5, D18). Read-only, no lock.
 *
 * <p>Every method joins the caller's transaction ({@link Propagation#MANDATORY}), so transfers are
 * read at the same instant as the wallets, the ledger and the review rows they are compared with.
 */
@Service
public class TransferReconciliationReads {

  private final TransferReconciliationRepository repository;

  public TransferReconciliationReads(TransferReconciliationRepository repository) {
    this.repository = Objects.requireNonNull(repository, "repository");
  }

  /**
   * A completed transfer; {@code reference} is its ledger transaction reference, and its posting
   * moves {@code amount} from the sender's to the receiver's customer account.
   */
  public record CompletedTransfer(
      UUID id, String reference, BigDecimal amount, UUID senderWalletId, UUID receiverWalletId) {

    public CompletedTransfer {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(reference, "reference");
      Objects.requireNonNull(amount, "amount");
      Objects.requireNonNull(senderWalletId, "senderWalletId");
      Objects.requireNonNull(receiverWalletId, "receiverWalletId");
    }
  }

  /**
   * What the {@code PENDING_REVIEW} transfers of one sender wallet to one recipient wallet hold on
   * one stored day.
   *
   * @param limitDate the transfers' stored {@code limit_date} (Y5)
   */
  public record HeldSum(
      UUID senderWalletId, UUID receiverWalletId, LocalDate limitDate, BigDecimal amount) {

    public HeldSum {
      Objects.requireNonNull(senderWalletId, "senderWalletId");
      Objects.requireNonNull(receiverWalletId, "receiverWalletId");
      Objects.requireNonNull(limitDate, "limitDate");
      Objects.requireNonNull(amount, "amount");
    }
  }

  /**
   * A transfer that entered review, with its status now, its customer-facing category ({@code
   * REVIEW_DECLINED} or {@code WALLET_UNAVAILABLE}, exactly when {@code REJECTED}) and the risk
   * assessment it references.
   */
  public record HeldTransferOutcome(
      UUID id, String status, String rejectionReasonCategory, UUID riskAssessmentId) {

    public HeldTransferOutcome {
      Objects.requireNonNull(id, "id");
      Objects.requireNonNull(status, "status");
      Objects.requireNonNull(riskAssessmentId, "riskAssessmentId");
    }
  }

  /** Every completed transfer. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public List<CompletedTransfer> completed() {
    return repository.completed().stream()
        .map(
            row ->
                new CompletedTransfer(
                    row.id(),
                    row.reference(),
                    row.amount(),
                    row.senderWalletId(),
                    row.receiverWalletId()))
        .toList();
  }

  /** Σ of the held transfers per sender wallet, recipient wallet and stored day. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public List<HeldSum> heldSums() {
    return repository.heldSums().stream()
        .map(
            row ->
                new HeldSum(
                    row.senderWalletId(), row.receiverWalletId(), row.limitDate(), row.amount()))
        .toList();
  }

  /** Every transfer that ever entered {@code PENDING_REVIEW}, still held or decided. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public List<HeldTransferOutcome> everHeld() {
    return repository.everHeld().stream()
        .map(
            row ->
                new HeldTransferOutcome(
                    row.id(), row.status(), row.rejectionReasonCategory(), row.riskAssessmentId()))
        .toList();
  }
}
