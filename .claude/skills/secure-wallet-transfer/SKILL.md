---
name: secure-wallet-transfer
description: Workflow and checklist for implementing or reviewing money movement in the modular wallet application — P2P transfers and simulated deposits/withdrawals. Covers documented amount/limit/ownership rules, single-transaction atomicity, ordered row locking with optimistic versioning, PostgreSQL idempotency, risk policy, atomic ledger posting, failure-scenario tests against PostgreSQL, and residual-risk reporting. Use for any change that can alter a balance.
argument-hint: "<flow, e.g. transfer | deposit | withdrawal>"
---

# Secure wallet transfer and money movement

Only the approved documentation defines financial rules. If a rule you need is missing or two docs disagree, stop and report the conflict; never pick a value yourself. The MVP processes **simulated money only**.

## Prerequisites
Read these before touching code:
- MVP money-path decisions: `documentations/06. PortfolioMvpDecisions.md` MVP-ADR-03 (atomic ledger), -06 (idempotency), -08 (locking), -10 (KYC before wallet), -12 (risk policy, roles), -13 (simulated deposits/withdrawals), -18 (undefined single limits), -19 (non-disclosure).
- Ledger design and invariants: `documentations/01. HighLevelArchitecture.md` §6.2–§6.5; flow and failure intent in §4.2–§4.3 (Kafka and internal APIs superseded by doc 06).
- Contracts: `documentations/02. ApiDocumentation.md` §1.4 (idempotency), §2.3–§2.4.
- Rules: `documentations/05. ProductBusiness.md` §4 (Wallet, Limits, Deposit/withdrawal, Transfer, Ledger, Fraud).
- Threats: `documentations/03. SecurityComplianceGovernance.md` §4.2 T3, T5, T6, T7, T10.

## Workflow

### 1. Validate the request
- Amount > 0, at most 4 decimals, `BigDecimal` end to end; currency explicit and `THB` (`CURRENCY_MISMATCH`, `INVALID_AMOUNT`).
- The caller owns the source wallet (JWT subject → customer → wallet); never trust a wallet id from the request body for the debit side.
- Account `ACTIVE`, KYC approved (`KYC_REQUIRED`), wallet `ACTIVE` (`WALLET_FROZEN`).
- Transfer rules: no self-transfer (`SELF_TRANSFER`); receiver exists and is active. A receiver that doesn't exist or isn't eligible must not reveal whether the account exists beyond what doc 02 allows.
- Single and daily limits from the wallet's configured limits (`SINGLE_LIMIT_EXCEEDED`, `DAILY_LIMIT_EXCEEDED`). Limits are configuration, never literals; failed transfers do not consume the daily limit. Values not defined in the docs are an open question (MVP-ADR-18).

### 2. Idempotency
- `Idempotency-Key` required; store key + request hash (`varchar(64)`) and the original response in PostgreSQL.
- Same key + same body → return the original status and body. Same key + different body → `409 IDEMPOTENCY_KEY_REUSED`.
- A unique constraint on the key (and on the business `reference`) is the final guard; a concurrent duplicate that hits the constraint must resolve to the replay, not a 500 or a second movement.

### 3. Risk
Evaluate the documented risk policy before moving money (MVP-ADR-12, proposed). `BLOCK` → no balance change, error `RISK_BLOCKED`, audited.

### 4. Atomic movement
All of the following happen in **one database transaction**, in the owning module's application service:
1. Lock both wallets with `SELECT … FOR UPDATE` in **ascending wallet-id order** (prevents deadlocks).
2. Re-check balance and limits on the locked rows.
3. Update snapshots with the optimistic `version` check (`UPDATE … WHERE id = ? AND version = ?`; zero rows → fail).
4. Post the balanced ledger transaction (Σ DEBIT = Σ CREDIT, ≥ 2 entries, unique `transaction_reference`; templates in doc 01 §6.4).
5. Write the business record (transfer/deposit/withdrawal) and the audit row.
6. Commit. Build the response (`ledgerStatus = POSTED`) only after commit.
- `CHECK (available_balance >= 0)` stays as the last guard; never overdraft.
- Any exception rolls back everything; there is no compensation step because nothing commits partially.
- Notifications are sent after commit and can never roll money back.

### 5. Test failure scenarios (Testcontainers PostgreSQL)
At minimum (use `testing-workflow`):
- each validation error code above;
- authorization: another customer's wallet → identical 404; unauthenticated → 401;
- insufficient funds leaves both balances and the ledger unchanged;
- idempotent replay returns the same response; key reuse → 409; **concurrent duplicate requests** produce exactly one movement;
- **rollback**: a failure injected after the debit (e.g. in ledger posting) leaves no snapshot change, no ledger entries and no business record;
- **concurrent transfers**: many parallel transfers from one wallet never overdraw; opposite-direction transfers between two wallets don't deadlock; total money is conserved;
- ledger balanced after every test; UPDATE/DELETE on ledger tables is rejected;
- reconciliation (snapshot vs ledger-derived balance) reports no mismatch.

## Verification
- Every rule above is either implemented with a test or listed as not applicable to this flow.
- No code path changes a balance outside the single transactional service method.
- `./mvnw verify` passes; report actual counts. A passing smoke test is never evidence of financial correctness.

## Completion criteria
Report:
1. Rules implemented, each with doc reference and test name.
2. Test results (actual commands and counts).
3. **Unresolved risks**: rules that could not be enforced, documentation conflicts, assumptions, and residual risks (e.g. single-instance rate limiting, no step-up authentication; doc 06 §4).
4. Confirmation that `security-reviewer` review is requested.
