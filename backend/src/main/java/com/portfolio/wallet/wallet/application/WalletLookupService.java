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
 */
@Service
public class WalletLookupService {

  static final String KYC_REQUIRED = "KYC approval is required before looking up a wallet";
  static final String FROZEN = "Wallet is frozen";
  static final String INVALID_NUMBER = "walletNumber: must be 11 digits";
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
    InetAddress clientIp = ClientAddresses.parse(remoteAddress);
    if (clientIp == null) {
      throw new IllegalStateException("remote address is not an IP literal");
    }
    String ipKey = ipKey(clientIp);
    Outcome outcome =
        Objects.requireNonNull(
            transactions.execute(status -> attempt(callerId, ipKey, clientIp, walletNumber)));
    return outcome.result();
  }

  private Outcome attempt(UUID callerId, String ipKey, InetAddress clientIp, String number) {
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
    attempts.insert(callerId, ipKey);

    // Second validation step (X2): a wrong check digit cannot be a wallet. It is counted and
    // audited like any miss, with no wallet query, and gives the same 404.
    Optional<WalletRow> target =
        !WalletNumber.hasValidCheckDigit(number)
            ? Optional.empty()
            : wallets
                .findByWalletNumber(number)
                .filter(row -> WalletStatus.valueOf(row.status()) == WalletStatus.ACTIVE)
                .filter(row -> !row.customerId().equals(callerId));
    Optional<String> name = target.flatMap(row -> names.maskedLegalName(row.customerId()));
    if (target.isPresent() && name.isEmpty()) {
      // An approved wallet always has a legal name (V8); answer like any other miss.
      log.warn("Wallet lookup target has no legal name");
    }
    if (name.isEmpty()) {
      audit.record(entry(callerId, clientIp, AuditResult.FAILURE, null, "NOT_FOUND"));
      return Outcome.NotFound.INSTANCE;
    }
    audit.record(
        entry(callerId, clientIp, AuditResult.SUCCESS, target.get().id().toString(), "FOUND"));
    return new Outcome.Found(new WalletLookupView(name.get(), masked(number)));
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

  /** Turned into a response or an exception only after the commit, so the attempt stays. */
  private sealed interface Outcome {

    WalletLookupView result();

    record Found(WalletLookupView view) implements Outcome {
      @Override
      public WalletLookupView result() {
        return view;
      }
    }

    record Limited(long retryAfterSeconds) implements Outcome {
      @Override
      public WalletLookupView result() {
        throw new RateLimitedException(retryAfterSeconds);
      }
    }

    enum NotFound implements Outcome {
      INSTANCE;

      @Override
      public WalletLookupView result() {
        throw ApiException.notFound();
      }
    }
  }
}
