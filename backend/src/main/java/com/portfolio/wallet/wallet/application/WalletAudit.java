package com.portfolio.wallet.wallet.application;

import com.portfolio.wallet.audit.application.ActorType;
import com.portfolio.wallet.audit.application.AuditEntry;
import com.portfolio.wallet.audit.application.AuditResult;
import com.portfolio.wallet.wallet.domain.Wallet;
import java.net.InetAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds this module's audit entries. Values are states and ids only; the wallet number is left
 * out, because it is the key of the recipient lookup (documentations/06 MVP-ADR-20).
 */
final class WalletAudit {

  static final String WALLET = "WALLET";
  static final String WALLET_CREATE = "WALLET_CREATE";
  static final String WALLET_FREEZE = "WALLET_FREEZE";
  static final String WALLET_UNFREEZE = "WALLET_UNFREEZE";

  private static final String STATUS = "status";

  private WalletAudit() {}

  /**
   * A real status change of the wallet and its ledger account (MVP-ADR-10 amendment). A staff
   * action records the staff member as the actor, the customer, and the reason they gave (doc 02
   * §2.7: freeze and unfreeze require a reason, audited); a customer's own freeze has no reason.
   *
   * @param reason null for the customer's own freeze
   */
  static AuditEntry statusChanged(
      String action,
      ActorType actorType,
      UUID actorId,
      Wallet wallet,
      String oldStatus,
      String reason,
      InetAddress clientIp) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put(STATUS, wallet.status().name());
    value.put("ledgerAccountStatus", wallet.status().name());
    value.put("customerId", wallet.customerId().toString());
    if (reason != null) {
      value.put("reason", reason);
    }
    return AuditEntry.of(actorType, action, WALLET, AuditResult.SUCCESS)
        .withActor(actorId)
        .withResourceId(wallet.id().toString())
        .withClientIp(clientIp)
        .withOldValue(Map.of(STATUS, oldStatus))
        .withNewValue(value);
  }

  /** The reviewer whose KYC approval opened the wallet is the actor (MVP-ADR-10). */
  static AuditEntry created(
      Wallet wallet, UUID ledgerAccountId, UUID reviewerId, InetAddress clientIp) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("customerId", wallet.customerId().toString());
    value.put("status", wallet.status().name());
    value.put("tier", wallet.tier().name());
    value.put("currency", wallet.currency().getCurrencyCode());
    value.put("ledgerAccountId", ledgerAccountId.toString());
    return AuditEntry.of(ActorType.STAFF, WALLET_CREATE, WALLET, AuditResult.SUCCESS)
        .withActor(reviewerId)
        .withResourceId(wallet.id().toString())
        .withClientIp(clientIp)
        .withNewValue(value);
  }
}
