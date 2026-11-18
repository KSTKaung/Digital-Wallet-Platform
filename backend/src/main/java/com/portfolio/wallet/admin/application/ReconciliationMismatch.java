package com.portfolio.wallet.admin.application;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * One mismatch found by the reconciliation (documentations/06 MVP-ADR-03).
 *
 * <p>{@code ids} holds identifiers only, in a fixed order: internal ids ({@code UUID}), a stored
 * business day ({@code LocalDate}) and the fixed names of record types and operations. Never an
 * amount, a balance, a reference, a wallet number, a name, a description or a reviewer's text:
 * balances and transactions are Confidential data, masked in logs (doc 03 §5.1), and an operator
 * finds the values from the ids.
 *
 * @param kind what disagrees, and so which check found it
 * @param ids the identifiers of the rows involved, in insertion order
 */
public record ReconciliationMismatch(Kind kind, Map<String, Object> ids) {

  public ReconciliationMismatch {
    Objects.requireNonNull(kind, "kind");
    ids = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(ids, "ids")));
  }

  /** The check of MVP-ADR-03 that found it: {@code 1}–{@code 5}, or {@code SUM}. */
  public String check() {
    return kind.check();
  }

  /** Whether {@code id} is one of the identifiers. */
  public boolean involves(Object id) {
    return ids.containsValue(id);
  }

  /** The identifiers as {@code name=value, ...}, as logged. */
  public String describeIds() {
    return ids.entrySet().stream()
        .map(entry -> entry.getKey() + "=" + entry.getValue())
        .collect(Collectors.joining(", "));
  }

  /** The mismatches, each with the check it belongs to. */
  public enum Kind {
    /** Check 1: available + reserved differs from Σ CREDIT − Σ DEBIT of the wallet's account. */
    WALLET_BALANCE_DIFFERS("1"),
    /** Check 1: a wallet has no customer ledger account (they are created together). */
    WALLET_WITHOUT_LEDGER_ACCOUNT("1"),
    /** Check 1: a customer ledger account names no existing wallet. */
    LEDGER_ACCOUNT_WITHOUT_WALLET("1"),
    /**
     * Check 1: a wallet has more than one customer ledger account (the partial unique index refuses
     * it; only a dropped index could let one through).
     */
    DUPLICATE_CUSTOMER_ACCOUNT("1"),
    /** Check 2: a completed deposit, withdrawal or transfer has no ledger transaction. */
    MOVEMENT_NOT_POSTED("2"),
    /** Check 2: its reference is posted, but as a ledger transaction of another type. */
    MOVEMENT_POSTED_AS_OTHER_TYPE("2"),
    /** Check 2: the posting's Σ DEBIT or Σ CREDIT differs from the record's amount. */
    POSTING_AMOUNT_DIFFERS("2"),
    /**
     * Check 2: a posting does not move between exactly the accounts of its doc 01 §6.4 template: a
     * deposit from {@code SYS-CASH-CLEARING} to its wallet's customer account, a withdrawal the
     * reverse, a transfer from the sender wallet's customer account to the receiver wallet's.
     */
    POSTING_ACCOUNTS_DIFFER("2"),
    /** Check 2: a ledger transaction belongs to no completed deposit, withdrawal or transfer. */
    POSTING_WITHOUT_MOVEMENT("2"),
    /** Check 3: a wallet's reserved balance differs from Σ of its held transfers. */
    RESERVED_BALANCE_DIFFERS("3"),
    /**
     * Check 3: held transfers name a wallet that does not exist; {@code side} is {@code SENDER} or
     * {@code RECIPIENT}.
     */
    HOLD_OF_UNKNOWN_WALLET("3"),
    /** Check 4: a day's reserved usage differs from Σ of the transfers held on that stored day. */
    RESERVED_USAGE_DIFFERS("4"),
    /** Check 5: a review row's outcome does not match its transfer's status and category. */
    REVIEW_DISAGREES_WITH_TRANSFER("5"),
    /** Check 5: a transfer left {@code PENDING_REVIEW} without a review row. */
    DECIDED_WITHOUT_REVIEW("5"),
    /**
     * Check 5: a review row exists for a transfer that never left {@code PENDING_REVIEW}: still
     * held, never held, or missing.
     */
    REVIEW_WITHOUT_DECIDED_TRANSFER("5"),
    /**
     * Check 5: a transfer has more than one review row (the unique index refuses it; only a dropped
     * index could let one through).
     */
    DUPLICATE_REVIEW("5"),
    /**
     * Check 5: a review row references another risk assessment than its transfer ({@code
     * risk_reviews.assessment_id} differs from {@code transfers.risk_assessment_id}).
     */
    REVIEW_ASSESSMENT_DIFFERS("5"),
    /** Σ: a ledger transaction is unbalanced or has fewer than two entries. */
    TRANSACTION_UNBALANCED("SUM"),
    /** Σ: Σ DEBIT differs from Σ CREDIT over the whole ledger. */
    LEDGER_TOTAL_UNBALANCED("SUM");

    private final String check;

    Kind(String check) {
      this.check = check;
    }

    public String check() {
      return check;
    }
  }
}
