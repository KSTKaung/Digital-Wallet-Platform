package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.audit.application.ActorType;
import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.common.database.TransactionTimeouts;
import com.portfolio.wallet.common.error.ApiException;
import com.portfolio.wallet.common.error.ErrorCode;
import com.portfolio.wallet.common.error.RateLimitedException;
import com.portfolio.wallet.common.security.ClientAddresses;
import com.portfolio.wallet.wallet.domain.WalletNumber;
import com.portfolio.wallet.wallet.domain.WalletStatus;
import com.portfolio.wallet.wallet.persistence.LookupAttemptRepository;
import com.portfolio.wallet.wallet.persistence.LookupAttemptRepository.WindowCount;
import com.portfolio.wallet.wallet.persistence.WalletRepository;
import com.portfolio.wallet.wallet.persistence.WalletRow;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Recipient lookup by exact wallet number (documentations/02 §2.3; doc 06 MVP-ADR-19, -20; doc 07
 * D26; doc 08 §4B.2 Q3, §4B.4 AC 10, 11).
 *
 * <ol>
 *   <li>Validation step 1, format (owner, 2026-11-04, X2): the number must be 11 ASCII digits
 *       ({@code 400 VALIDATION_ERROR}); nothing is counted or audited.
 *   <li>The caller must be able to look up: no wallet (no KYC approval) is {@code 403
 *       KYC_REQUIRED}, a frozen wallet {@code 409 WALLET_FROZEN} (MVP-ADR-20: only KYC-approved
 *       callers with an {@code ACTIVE} wallet). Nothing is counted.
 *   <li>One transaction: the caller's and the client IP's attempts are serialised by advisory
 *       locks, user first; if either has used its limit in the sliding window (10 per user, 30 per
 *       IP, from configuration), {@code 429 RATE_LIMITED} with {@code Retry-After}, and nothing is
 *       recorded. Otherwise the attempt is recorded, so found and not-found lookups both count, and
 *       the counts live in PostgreSQL: they hold across restarts and instances.
 *   <li>Validation step 2, check digit (X2), after the attempt is recorded: a wrong Luhn digit
 *       ({@link WalletNumber#hasValidCheckDigit}) is a miss without a wallet query, counted and
 *       audited, with the same {@code 404} as a missing wallet.
 *   <li>The number is matched exactly. A missing, frozen or closed wallet, or the caller's own,
 *       gives the identical {@code 404} of {@link ApiException#notFound()}. A match returns the
 *       masked legal name, from {@code user.application} through {@link RecipientNames}, and the
 *       wallet number with only its last 4 digits visible.
 *   <li>Every recorded attempt has an audit row in the same transaction: result {@code SUCCESS}
 *       with the found wallet's internal id, or {@code FAILURE}. It never holds the wallet number
 *       or a name (AC 11).
 * </ol>
 *
 * The client IP is the container's remote address, which takes {@code X-Forwarded-For} only from
 * trusted proxies (MVP-ADR-20); an IPv6 address counts per /64, as in the in-memory limiter.
 *
 * <h2>Transfers count as lookups (Q5)</h2>
 *
 * {@link #resolveTransferRecipient} is the recipient step of {@code POST /transfers} (doc 08 §4C.2
 * Q3 step 3, Q5; doc 06 MVP-ADR-20 amendment "Transfers count as lookups"; doc 02 §1.8). It runs in
 * the <b>same transaction</b> as {@link #lookup}: the same advisory locks and the same per-user and
 * per-IP windows (Y1 clock), so the transfer endpoint is not a way around the lookup limit:
 *
 * <ul>
 *   <li><b>the limit applies to every resolution</b>: a caller over either window gets {@code 429}
 *       with {@code Retry-After} whether the recipient exists or not, so the answer never tells
 *       which (MVP-ADR-20); the refused request is neither counted nor audited;
 *   <li><b>only a recipient that is not found counts</b> (a wrong check digit, a missing, frozen or
 *       closed wallet): its attempt row and its {@code WALLET_LOOKUP} {@code FAILURE} audit row are
 *       written, then the identical {@code 404};
 *   <li>a found recipient, another customer's {@code ACTIVE} wallet or the caller's own ({@code 409
 *       SELF_TRANSFER}, after the 404 cases), is neither counted nor audited as a lookup; the
 *       transfer's own audit rows cover it;
 *   <li>the caller needs a wallet ({@code 403 KYC_REQUIRED}), but a frozen sender is not refused
 *       here: {@code WALLET_FROZEN} of the sender is a stored refusal of the pre-check (Q3 step 5).
 * </ul>
 *
 * A recipient found here but frozen or closed under the money transaction's lock gets the identical
 * {@code 404} and is not counted (owner, 2026-11-09, doc 08 §5).
 */
@Service
public class WalletLookupService {

  static final String KYC_REQUIRED = "KYC approval is required before looking up a wallet";
  static final String FROZEN = "Wallet is frozen";
  static final String INVALID_NUMBER = "walletNumber: must be 11 digits";
  static final String INVALID_RECIPIENT = "receiverWalletNumber: must be 11 digits";
  static final String SELF_TRANSFER = "Cannot transfer to your own wallet";
  static final String LOOKUP = "WALLET_LOOKUP";
  static final String WALLET = "WALLET";

  private static final Logger log = LoggerFactory.getLogger(WalletLookupService.class);
  private static final int VISIBLE_DIGITS = 4;
  private static final int IPV6_NETWORK_BYTES = 8;

  private final WalletRepository wallets;
  private final LookupAttemptRepository attempts;
  private final RecipientNames names;
  private final AuditService audit;
  private final LookupProperties properties;
  private final TransactionTemplate transactions;
  private final TransactionTimeouts timeouts;

  public WalletLookupService(
      WalletRepository wallets,
      LookupAttemptRepository attempts,
      RecipientNames names,
      AuditService audit,
      LookupProperties properties,
      PlatformTransactionManager transactionManager,
      TransactionTimeouts timeouts) {
    this.wallets = wallets;
    this.attempts = attempts;
    this.names = names;
    this.audit = audit;
    this.properties = properties;
    this.transactions = new TransactionTemplate(transactionManager);
    this.timeouts = Objects.requireNonNull(timeouts, "timeouts");
  }

  /**
   * @param remoteAddress the container's remote address of the request
   * @throws ApiException {@code VALIDATION_ERROR}, {@code KYC_REQUIRED}, {@code WALLET_FROZEN} or
   *     the identical {@code NOT_FOUND}
   * @throws RateLimitedException over a limit
   */
  public WalletLookupView lookup(UUID callerId, String walletNumber, String remoteAddress) {
    Objects.requireNonNull(callerId, "callerId");
    // First validation step (X2): the format only; not counted, not audited.
    if (!WalletNumber.hasValidFormat(walletNumber)) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, INVALID_NUMBER);
    }
    WalletRow caller =
        wallets
            .findByCustomer(callerId)
            .orElseThrow(() -> new ApiException(ErrorCode.KYC_REQUIRED, KYC_REQUIRED));
    if (WalletStatus.valueOf(caller.status()) == WalletStatus.FROZEN) {
      throw new ApiException(ErrorCode.WALLET_FROZEN, FROZEN);
    }
    if (WalletStatus.valueOf(caller.status()) != WalletStatus.ACTIVE) {
      // CLOSED is unreachable in the MVP (doc 07 D21).
      throw new IllegalStateException("caller wallet is neither active nor frozen");
    }
    return counted(callerId, walletNumber, remoteAddress, Purpose.LOOKUP).view();
  }

  /**
   * The recipient step of {@code POST /transfers} (Q3 steps 2 and 3, Q5): limited like {@link
   * #lookup}, counted and audited only when the recipient is not found; see the class
   * documentation.
   *
   * @param callerId the sender, the JWT subject
   * @param walletNumber the request's {@code receiverWalletNumber}, already format-checked by the
   *     web layer; checked again here
   * @param remoteAddress the container's remote address of the request
   * @return the recipient's wallet and customer ids and its masked name and number
   * @throws ApiException {@code VALIDATION_ERROR} (format; not counted), {@code KYC_REQUIRED} (no
   *     sender wallet; not counted), the identical {@code NOT_FOUND} (counted and audited) or
   *     {@code SELF_TRANSFER} (not counted, not audited as a lookup)
   * @throws RateLimitedException over a lookup limit (neither counted nor audited)
   */
  public TransferRecipient resolveTransferRecipient(
      UUID callerId, String walletNumber, String remoteAddress) {
    Objects.requireNonNull(callerId, "callerId");
    if (!WalletNumber.hasValidFormat(walletNumber)) {
      throw new ApiException(ErrorCode.VALIDATION_ERROR, INVALID_RECIPIENT);
    }
    // Q3 step 2: no wallet means no KYC approval (MVP-ADR-10); checked before anything is counted.
    // A frozen sender is refused by the pre-check (step 5), where WALLET_FROZEN is stored.
    if (wallets.findByCustomer(callerId).isEmpty()) {
      throw new ApiException(ErrorCode.KYC_REQUIRED, MoneyMovementService.KYC_REQUIRED);
    }
    Outcome.Found found = counted(callerId, walletNumber, remoteAddress, Purpose.TRANSFER);
    return new TransferRecipient(
        found.walletId(),
        found.customerId(),
        found.view().maskedName(),
        found.view().maskedWalletNumber());
  }

  /** The counted transaction shared by the lookup and the transfer's recipient step. */
  private Outcome.Found counted(
      UUID callerId, String walletNumber, String remoteAddress, Purpose purpose) {
    InetAddress clientIp = ClientAddresses.parse(remoteAddress);
    if (clientIp == null) {
      throw new IllegalStateException("remote address is not an IP literal");
    }
    String ipKey = ipKey(clientIp);
    Outcome outcome =
        Objects.requireNonNull(
            transactions.execute(
                status -> attempt(callerId, ipKey, clientIp, walletNumber, purpose)));
    return outcome.result();
  }

  private Outcome attempt(
      UUID callerId, String ipKey, InetAddress clientIp, String number, Purpose purpose) {
    // D14 (doc 07 open risk Z1): bounds the advisory-lock waits below; first statement.
    timeouts.apply();
    long window = properties.window().toSeconds();
    attempts.lockKeys(callerId, ipKey);
    WindowCount user = attempts.countForUser(callerId, window);
    WindowCount ip = attempts.countForIp(ipKey, window);
    boolean userLimited = user.count() >= properties.perUser();
    boolean ipLimited = ip.count() >= properties.perIp();
    if (userLimited || ipLimited) {
      long wait =
          Math.max(
              userLimited ? user.secondsUntilOldestLeaves() : 0,
              ipLimited ? ip.secondsUntilOldestLeaves() : 0);
      // The user id and the address stay out of the log.
      log.warn("Refused a wallet lookup by the rate limit: user={}, ip={}", userLimited, ipLimited);
      return new Outcome.Limited(wait);
    }
    boolean lookup = purpose == Purpose.LOOKUP;
    if (lookup) {
      // A lookup counts whatever it finds (MVP-ADR-20).
      attempts.insert(callerId, ipKey);
    }

    // Second validation step (X2): a wrong check digit cannot be a wallet. It is a miss without a
    // wallet query, counted and audited like any other, and gives the same 404.
    Optional<WalletRow> active =
        !WalletNumber.hasValidCheckDigit(number)
            ? Optional.empty()
            : wallets
                .findByWalletNumber(number)
                .filter(row -> WalletStatus.valueOf(row.status()) == WalletStatus.ACTIVE);
    if (!lookup && active.isPresent() && active.get().customerId().equals(callerId)) {
      // Q3 step 3: the sender's own wallet, after the 404 cases. Found, so neither counted nor
      // audited as a lookup (Q5); the transfer's own audit covers the request.
      return Outcome.Self.INSTANCE;
    }
    Optional<WalletRow> target = active.filter(row -> !row.customerId().equals(callerId));
    Optional<String> name = target.flatMap(row -> names.maskedLegalName(row.customerId()));
    if (target.isPresent() && name.isEmpty()) {
      // An approved wallet always has a legal name (V8); answer like any other miss.
      log.warn("Wallet lookup target has no legal name");
    }
    if (name.isEmpty()) {
      if (!lookup) {
        // Q5: only a transfer whose recipient is not found counts in the lookup windows. Inserted
        // by a statement after the locks and the window counts, so its stamp keeps Y1.
        attempts.insert(callerId, ipKey);
      }
      audit.record(entry(callerId, clientIp, AuditResult.FAILURE, null, "NOT_FOUND"));
      return Outcome.NotFound.INSTANCE;
    }
    if (!lookup) {
      // A found recipient of a transfer: not counted, not audited as a lookup (Q5).
      return new Outcome.Found(
          new WalletLookupView(name.get(), masked(number)),
          target.get().id(),
          target.get().customerId());
    }
    audit.record(
        entry(callerId, clientIp, AuditResult.SUCCESS, target.get().id().toString(), "FOUND"));
    return new Outcome.Found(
        new WalletLookupView(name.get(), masked(number)),
        target.get().id(),
        target.get().customerId());
  }

  /** Only the last 4 digits visible (MVP-ADR-20), the length kept: {@code *******8901}. */
  static String masked(String walletNumber) {
    int hidden = walletNumber.length() - VISIBLE_DIGITS;
    return "*".repeat(hidden) + walletNumber.substring(hidden);
  }

  /** IPv4 as it is; IPv6 as its /64 network address, as the in-memory per-IP limit counts. */
  static String ipKey(InetAddress address) {
    if (!(address instanceof Inet6Address)) {
      return address.getHostAddress();
    }
    byte[] bytes = address.getAddress();
    Arrays.fill(bytes, IPV6_NETWORK_BYTES, bytes.length, (byte) 0);
    try {
      return InetAddress.getByAddress(bytes).getHostAddress();
    } catch (UnknownHostException e) {
      // 16 bytes are always a valid IPv6 address.
      throw new IllegalStateException(e);
    }
  }

  private static AuditEntry entry(
      UUID callerId, InetAddress clientIp, AuditResult result, String resourceId, String outcome) {
    return AuditEntry.of(ActorType.CUSTOMER, LOOKUP, WALLET, result)
        .withActor(callerId)
        .withResourceId(resourceId)
        .withClientIp(clientIp)
        .withNewValue(Map.of("outcome", outcome));
  }

  /** Which endpoint resolves the number: the lookup itself, or {@code POST /transfers} (Q5). */
  private enum Purpose {
    LOOKUP,
    TRANSFER
  }

  /** Turned into a response or an exception only after the commit, so the attempt stays. */
  private sealed interface Outcome {

    Found result();

    record Found(WalletLookupView view, UUID walletId, UUID customerId) implements Outcome {
      @Override
      public Found result() {
        return this;
      }
    }

    record Limited(long retryAfterSeconds) implements Outcome {
      @Override
      public Found result() {
        throw new RateLimitedException(retryAfterSeconds);
      }
    }

    enum NotFound implements Outcome {
      INSTANCE;

      @Override
      public Found result() {
        throw ApiException.notFound();
      }
    }

    enum Self implements Outcome {
      INSTANCE;

      @Override
      public Found result() {
        throw new ApiException(ErrorCode.SELF_TRANSFER, SELF_TRANSFER);
      }
    }
  }
}
