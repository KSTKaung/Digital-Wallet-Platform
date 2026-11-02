package com.portfolio.wallet.auth.application;

import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.audit.application.AuditService;
import com.portfolio.wallet.auth.domain.OtpFailure;
import com.portfolio.wallet.auth.domain.OtpIssueDecision;
import com.portfolio.wallet.auth.domain.OtpIssueHistory;
import com.portfolio.wallet.auth.domain.OtpPolicy;
import com.portfolio.wallet.auth.domain.OtpPurpose;
import com.portfolio.wallet.auth.persistence.OtpChallengeRepository;
import com.portfolio.wallet.auth.persistence.OtpChallengeRow;
import com.portfolio.wallet.notification.application.EmailDispatcher;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issues and checks one-time codes for any purpose. Both methods run inside the caller's
 * transaction, so that a challenge commits with what it belongs to.
 */
@Service
public class OtpService {

  /** The one message for every refused code, whatever the purpose. */
  static final String INVALID_OTP_MESSAGE = "Invalid or expired code";

  private final OtpChallengeRepository challenges;
  private final OtpPolicy policy;
  private final KeyedHasher hasher;
  private final EmailDispatcher emails;
  private final AuditService audit;
  private final Clock clock;
  private final SecureRandom random = new SecureRandom();

  public OtpService(
      OtpChallengeRepository challenges,
      OtpPolicy policy,
      KeyedHasher hasher,
      EmailDispatcher emails,
      AuditService audit,
      Clock clock) {
    this.challenges = challenges;
    this.policy = policy;
    this.hasher = hasher;
    this.emails = emails;
    this.audit = audit;
    this.clock = clock;
  }

  /**
   * Creates a challenge if the limits allow it and cancels the previous one. Without a real
   * recipient it does the same work and sends nothing, so unknown identifiers behave alike and
   * their limits hold too; nobody can ever learn such a challenge's code.
   *
   * @param deliver whether the identifier belongs to an account that should receive the code
   * @return the decision, so the caller knows whether a code exists
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public OtpIssueDecision issue(
      String canonicalIdentifier, OtpPurpose purpose, boolean deliver, InetAddress clientIp) {
    String identifierHash = hasher.identifier(canonicalIdentifier);
    challenges.lockIdentifier(identifierHash);

    Instant now = clock.instant();
    OtpIssueHistory history =
        challenges.history(
            identifierHash, purpose, policy.hourlyWindowStart(now), policy.usedUpWindowStart(now));
    OtpIssueDecision decision = policy.decide(history, now);
    if (decision == OtpIssueDecision.ALLOWED) {
      String code = newCode();
      challenges.cancelLive(identifierHash, purpose, now);
      challenges.insert(
          identifierHash,
          purpose,
          hasher.otp(identifierHash, purpose.name(), code),
          now,
          policy.expiresAt(now));
      if (deliver) {
        emails.sendAfterCommit(AuthEmails.otp(canonicalIdentifier, code, policy.ttl()));
      }
    }
    audit.record(
        AuthAudit.anonymous(
            "OTP_ISSUE",
            AuthAudit.OTP_CHALLENGE,
            identifierHash,
            decision == OtpIssueDecision.ALLOWED ? AuditResult.SUCCESS : AuditResult.DENIED,
            decision.name(),
            clientIp));
    return decision;
  }

  /**
   * Holds the identifier until the transaction ends. Registration takes the phone lock first and
   * then this one; every other path that issues, cancels or checks a code takes this first. So a
   * check cannot overlap a re-registration that replaces what the code belongs to.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void lockIdentifier(String canonicalIdentifier) {
    challenges.lockIdentifier(hasher.identifier(canonicalIdentifier));
  }

  /** Ends any code still live, whatever the limits say about issuing another. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void cancelLive(String canonicalIdentifier, OtpPurpose purpose, Instant now) {
    String identifierHash = hasher.identifier(canonicalIdentifier);
    challenges.lockIdentifier(identifierHash);
    challenges.cancelLive(identifierHash, purpose, now);
  }

  /** A check for a purpose where the code alone is enough. */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<OtpFailure> check(String canonicalIdentifier, OtpPurpose purpose, String code) {
    return check(canonicalIdentifier, purpose, code, true);
  }

  /**
   * Checks a code and counts the attempt under the challenge's row lock, so concurrent wrong
   * guesses cannot exceed the cap. The caller must commit even when this returns a failure, and
   * reports it only afterwards.
   *
   * @param passwordAccepted whether the password this purpose also requires was right; a wrong one
   *     costs an attempt like a wrong code, so that it cannot be guessed here without limit
   * @return the failure, or empty when the code was right and the challenge is now consumed
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<OtpFailure> check(
      String canonicalIdentifier, OtpPurpose purpose, String code, boolean passwordAccepted) {
    String identifierHash = hasher.identifier(canonicalIdentifier);
    challenges.lockIdentifier(identifierHash);
    String presented = hasher.otp(identifierHash, purpose.name(), code);
    Optional<OtpChallengeRow> latest = challenges.latestForUpdate(identifierHash, purpose);
    if (latest.isEmpty()) {
      return Optional.of(OtpFailure.NO_CHALLENGE);
    }
    OtpChallengeRow challenge = latest.get();
    Instant now = clock.instant();
    Optional<OtpFailure> refusal = policy.refusal(challenge.state(), now);
    if (refusal.isPresent()) {
      return refusal;
    }
    boolean rightCode = KeyedHasher.sameDigest(presented, challenge.otpHash());
    if (rightCode && passwordAccepted) {
      challenges.consume(challenge.id(), now);
      return Optional.empty();
    }
    int attempts = challenge.state().attempts() + 1;
    challenges.recordWrongAttempt(challenge.id(), attempts, policy.isUsedUp(attempts) ? now : null);
    return Optional.of(rightCode ? OtpFailure.WRONG_PASSWORD : OtpFailure.WRONG_CODE);
  }

  private String newCode() {
    StringBuilder code = new StringBuilder(policy.digits());
    for (int i = 0; i < policy.digits(); i++) {
      code.append(random.nextInt(10));
    }
    return code.toString();
  }
}
