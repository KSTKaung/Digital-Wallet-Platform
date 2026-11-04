package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.audit.application.ActorType;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.ledger.application.LedgerAccountService;
import com.portfolio.wallet.notification.application.EmailKind;
import com.portfolio.wallet.notification.application.NotificationOutbox;
import com.portfolio.wallet.wallet.domain.Wallet;
import com.portfolio.wallet.wallet.domain.WalletStatus;
import com.portfolio.wallet.wallet.persistence.WalletRepository;
import com.portfolio.wallet.wallet.persistence.WalletRow;
import java.net.InetAddress;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Freeze and unfreeze (documentations/02 §2.3, §2.7; doc 06 MVP-ADR-10 amendment; doc 08 §4B.2 Q9).
 * The customer may freeze their own wallet; only {@code ADMIN} unfreezes, with a reason. {@code
 * CLOSED} is unreachable in the MVP and final (doc 07 D21): no path here leads to or out of it.
 *
 * <p>One transaction per change, in the lock order of every money flow (doc 07 §8): the wallet row
 * {@code FOR UPDATE} first, then its {@code CUSTOMER} ledger account {@code FOR UPDATE} through
 * {@code ledger.application}. A posting locks the same wallet first and the account ({@code FOR
 * SHARE}, V2's entry trigger) second, so the two serialise and cannot deadlock, and a posting that
 * comes after a freeze finds the account {@code FROZEN} and is refused. The wallet's status, its
 * {@code version}, the account's status, the audit row and the email's outbox row commit together
 * or not at all.
 *
 * <p>Repeating a change is idempotent: freezing a {@code FROZEN} wallet or unfreezing an {@code
 * ACTIVE} one returns the current status, changes nothing and writes no audit or outbox row. The
 * request's outcome is the same either way, so a retry or a double click is not an error.
 *
 * <p>A staff change that really changes the status emails the customer (MVP-ADR-10 amendment;
 * MVP-ADR-25): an outbox row is written as the transaction's last step, so a rollback sends
 * nothing, and the dispatcher sends it after commit, with retries. No address is looked up here, so
 * none is read while the wallet is locked. The row lock makes the status check and the change one
 * step: of concurrent requests only the one that changed the status writes a row. The customer's
 * own freeze sends nothing; they made it themselves.
 */
@Service
public class WalletStatusService {

  static final String NOT_FOUND = "Wallet not found";

  private static final Pattern FORBIDDEN = Pattern.compile("[\\p{Cc}\\p{Cs}\\p{Cf}]");
  private static final Pattern LETTER_OR_DIGIT = Pattern.compile("[\\p{L}\\p{N}]");

  private final WalletRepository wallets;
  private final LedgerAccountService ledgerAccounts;
  private final AuditService audit;
  private final NotificationOutbox outbox;

  public WalletStatusService(
      WalletRepository wallets,
      LedgerAccountService ledgerAccounts,
      AuditService audit,
      NotificationOutbox outbox) {
    this.wallets = wallets;
    this.ledgerAccounts = ledgerAccounts;
    this.audit = audit;
    this.outbox = outbox;
  }

  /**
   * The customer freezes their own wallet (doc 02 §2.3: customer self-service). Without a wallet,
   * which means before KYC approval, it is {@code 404 WALLET_NOT_FOUND}, as for the wallet reads.
   * No email is sent.
   */
  @Transactional
  public WalletStatusView freezeOwn(UUID customerId, InetAddress clientIp) {
    return change(
        customerId,
        Change.FREEZE,
        ActorType.CUSTOMER,
        customerId,
        null,
        clientIp,
        () -> new ApiException(ErrorCode.WALLET_NOT_FOUND, NOT_FOUND));
  }

  /**
   * Staff freeze a customer's wallet. An unknown customer and a customer without a wallet give the
   * same {@link ApiException#notFound()} (MVP-ADR-19). A real change queues the customer's email.
   *
   * @param reason required; recorded in the audit row, never in the email
   */
  @Transactional
  public WalletStatusView freezeByStaff(
      UUID staffId, UUID customerId, String reason, InetAddress clientIp) {
    return change(
        customerId,
        Change.FREEZE,
        ActorType.STAFF,
        staffId,
        requireReason(reason),
        clientIp,
        ApiException::notFound);
  }

  /** Staff unfreeze; the only way back to {@code ACTIVE}. Otherwise as {@link #freezeByStaff}. */
  @Transactional
  public WalletStatusView unfreezeByStaff(
      UUID staffId, UUID customerId, String reason, InetAddress clientIp) {
    return change(
        customerId,
        Change.UNFREEZE,
        ActorType.STAFF,
        staffId,
        requireReason(reason),
        clientIp,
        ApiException::notFound);
  }

  private WalletStatusView change(
      UUID customerId,
      Change change,
      ActorType actorType,
      UUID actorId,
      String reason,
      InetAddress clientIp,
      Supplier<ApiException> missing) {
    Objects.requireNonNull(customerId, "customerId");
    Objects.requireNonNull(actorId, "actorId");
    WalletRow row = wallets.findByCustomerForUpdate(customerId).orElseThrow(missing);
    Wallet wallet = WalletRows.toDomain(row);
    if (wallet.status() == change.target) {
      return new WalletStatusView(wallet.status().name());
    }
    String oldStatus = wallet.status().name();
    // Throws for CLOSED, which is unreachable in the MVP: a 500 rather than a silent success.
    change.wallet.accept(wallet);
    // Under the row lock the version cannot have moved; 0 rows would mean a path skipped the lock
    // (doc 07 §8 recommendation: roll back, no retry).
    if (wallets.updateStatus(wallet.id(), wallet.status().name(), wallet.version()) != 1) {
      throw new IllegalStateException("wallet version changed under its row lock");
    }
    change.ledger(ledgerAccounts, wallet.id());
    audit.record(
        WalletAudit.statusChanged(
            change.action, actorType, actorId, wallet, oldStatus, reason, clientIp));
    if (actorType == ActorType.STAFF) {
      outbox.enqueue(wallet.customerId(), change.email);
    }
    return new WalletStatusView(wallet.status().name());
  }

  /**
   * The web layer has checked the shape (required, at most 500, no control or format characters, a
   * letter or digit); this repeats the content rules for any other caller. Messages name the field,
   * never the value.
   */
  private static String requireReason(String reason) {
    if (reason == null || reason.isBlank()) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, "reason: must not be blank");
    }
    if (FORBIDDEN.matcher(reason).find()) {
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR, "reason: must not contain control or format characters");
    }
    if (!LETTER_OR_DIGIT.matcher(reason).find()) {
      throw new ApiException(
          ErrorCode.VALIDATION_ERROR, "reason: must contain a letter or a digit");
    }
    return reason;
  }

  private enum Change {
    FREEZE(WalletStatus.FROZEN, WalletAudit.WALLET_FREEZE, Wallet::freeze, EmailKind.WALLET_FROZEN),
    UNFREEZE(
        WalletStatus.ACTIVE,
        WalletAudit.WALLET_UNFREEZE,
        Wallet::unfreeze,
        EmailKind.WALLET_UNFROZEN);

    private final WalletStatus target;
    private final String action;
    private final Consumer<Wallet> wallet;
    private final EmailKind email;

    Change(WalletStatus target, String action, Consumer<Wallet> wallet, EmailKind email) {
      this.target = target;
      this.action = action;
      this.wallet = wallet;
      this.email = email;
    }

    void ledger(LedgerAccountService accounts, UUID walletId) {
      if (this == FREEZE) {
        accounts.freezeCustomerAccount(walletId);
      } else {
        accounts.unfreezeCustomerAccount(walletId);
      }
    }
  }
}
