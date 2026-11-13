-- V11: PR 5a step 1 (documentations/08 §4C; doc 06 MVP-ADR-06, -24, -25 amendments of 2026-11-06).
-- V1–V10 are unchanged. The outbox change is a superset of the old check; the transfer constraints
-- are new rules on tables the application has never written before PR 5.
--
-- Locks: each ALTER TABLE takes an ACCESS EXCLUSIVE lock on its table for the length of one scan of
-- the existing rows, and Flyway runs this file in one transaction, so a NOT VALID / VALIDATE split
-- would gain nothing here (owner, 2026-11-05, AA1). transfer.transfers and
-- transfer.idempotency_records hold no application rows yet, and the outbox is read by the
-- dispatcher in short transactions that wait at most this scan. A later kind change on a populated
-- outbox should add its check NOT VALID in one migration and VALIDATE it in the next.

-- Money emails (PR 5 plan Q9) ------------------------------------------------------------------
-- Deposit, withdrawal and transfer emails to customers, and the 12-hour review reminder to
-- compliance officers. There is deliberately no kind for a risk BLOCK, and none that tells a
-- receiver about a transfer before it completes (Q9). The new constraint lists the old kinds too, so
-- it is a superset of the one it replaces.
ALTER TABLE notification.notifications
  DROP CONSTRAINT ck_notifications_kind,
  ADD CONSTRAINT ck_notifications_kind
    CHECK (kind IN (
      'KYC_APPROVED', 'KYC_REJECTED', 'WALLET_FROZEN', 'WALLET_UNFROZEN',
      'DEPOSIT_COMPLETED', 'WITHDRAWAL_COMPLETED',
      'TRANSFER_SENT', 'TRANSFER_RECEIVED', 'TRANSFER_HELD',
      'TRANSFER_REJECTED', 'TRANSFER_CANCELLED', 'TRANSFER_EXPIRED',
      'TRANSFER_REVIEW_EXPIRING'));

-- Transfers: what the customer may see, and one assessment per transfer (owner, 2026-11-06, D1) --
-- The database, not only the application, keeps these rows valid. transfer.transfers has never been
-- written by the application before PR 5, so the scan of this ALTER runs over no rows.
ALTER TABLE transfer.transfers
  -- The customer-facing category of a REJECTED transfer is one of the two generic values (Q10), so
  -- a rule code or the reviewer's text can never be stored where a customer reads it (MVP-ADR-24).
  -- ck_transfers_rejected_category (V4) still requires it exactly when the transfer is REJECTED.
  -- REVIEW_DECLINED: a compliance officer rejected it (staff rejection). WALLET_UNAVAILABLE: an
  -- approval found the sender or recipient wallet frozen or closed (system rejection).
  ADD CONSTRAINT ck_transfers_rejection_reason_category
    CHECK (rejection_reason_category IN ('REVIEW_DECLINED', 'WALLET_UNAVAILABLE')),
  -- One risk assessment authorises at most one transfer; risk.risk_assessments already holds at
  -- most one assessment per transfer (uq_risk_assessments_transfer, V5).
  ADD CONSTRAINT uq_transfers_risk_assessment UNIQUE (risk_assessment_id);

-- Review outcomes: who acted and why, staff and system rejections apart (owner, 2026-11-06, D2, D3)
-- Every review row records the actual outcome (action), the decision source (decided_by), the
-- requested action, the triggering actor (actor_user_id) and the reason: the staff rejection
-- category with the reviewer's text, or the system reason. One responsibility per column (doc 06
-- MVP-ADR-24 amendment, "Review outcomes"):
--   reason_category  staff rejection category only (MVP: STAFF_REJECTION, S1)
--   reason           the reviewer's own text, only for a staff rejection; staff-only
--   system_reason    system reason code only, exactly when the system decided
-- The customer-facing category is transfer.transfers.rejection_reason_category, not here.
-- risk.risk_reviews has never been written by the application before PR 5, so decided_by can be
-- NOT NULL without a default and every check runs over no rows.
--
-- A CHECK passes when its expression is NULL, so every comparison on a nullable column below uses
-- IS NOT DISTINCT FROM, or IS NOT NULL first: a NULL never slips a row past a rule.
ALTER TABLE risk.risk_reviews
  ADD COLUMN decided_by       varchar(10) NOT NULL,
  ADD COLUMN requested_action varchar(20),
  ADD COLUMN system_reason    varchar(40),
  ADD CONSTRAINT ck_risk_reviews_decided_by
    CHECK (decided_by IN ('STAFF', 'CUSTOMER', 'SYSTEM')),
  ADD CONSTRAINT ck_risk_reviews_requested_action
    CHECK (requested_action IN ('APPROVE', 'REJECT', 'CANCEL')),
  -- MVP limitation (owner, 2026-11-06, S1): one staff category until a formal classification policy
  -- defines more; a new category is a new migration widening this list.
  ADD CONSTRAINT ck_risk_reviews_reason_category
    CHECK (reason_category IN ('STAFF_REJECTION')),
  ADD CONSTRAINT ck_risk_reviews_system_reason
    CHECK (system_reason IN (
      'SENDER_WALLET_FROZEN', 'SENDER_WALLET_CLOSED',
      'RECIPIENT_WALLET_FROZEN', 'RECIPIENT_WALLET_CLOSED',
      'REVIEW_DEADLINE_PASSED')),
  -- A reason is a written explanation, never only blanks (the application also applies the KYC
  -- reason text rules of MVP-ADR-10).
  ADD CONSTRAINT ck_risk_reviews_reason_text
    CHECK (reason IS NULL OR btrim(reason) <> ''),
  -- Staff rejection, both ways and for each column on its own: exactly a staff-decided REJECT has a
  -- staff category, and exactly it has the reviewer's text. Comparing each column separately keeps
  -- a system decision from carrying staff text without a category (or the other way round).
  ADD CONSTRAINT ck_risk_reviews_staff_rejection
    CHECK ((action = 'REJECT' AND decided_by = 'STAFF') = (reason_category IS NOT NULL)
           AND (action = 'REJECT' AND decided_by = 'STAFF') = (reason IS NOT NULL)),
  -- System decision, both ways: exactly what the system decided carries a system reason.
  ADD CONSTRAINT ck_risk_reviews_system_decision
    CHECK ((decided_by = 'SYSTEM') = (system_reason IS NOT NULL)),
  -- The seven cases of D2; a row outside them cannot be stored.
  ADD CONSTRAINT ck_risk_reviews_outcome
    CHECK (
      -- 1 staff approval
      (action = 'APPROVE' AND decided_by = 'STAFF'
        AND requested_action IS NOT DISTINCT FROM 'APPROVE' AND actor_user_id IS NOT NULL)
      -- 2 staff rejection
      OR (action = 'REJECT' AND decided_by = 'STAFF'
        AND requested_action IS NOT DISTINCT FROM 'REJECT' AND actor_user_id IS NOT NULL)
      -- 3 system rejection: an approval found a frozen or closed wallet
      OR (action = 'REJECT' AND decided_by = 'SYSTEM'
        AND requested_action IS NOT DISTINCT FROM 'APPROVE' AND actor_user_id IS NOT NULL
        AND system_reason IS NOT NULL AND system_reason <> 'REVIEW_DEADLINE_PASSED')
      -- 4 customer cancel
      OR (action = 'CANCEL' AND decided_by = 'CUSTOMER'
        AND requested_action IS NOT DISTINCT FROM 'CANCEL' AND actor_user_id IS NOT NULL)
      -- 5 expiry by the job: no request, no actor
      OR (action = 'EXPIRE' AND decided_by = 'SYSTEM' AND requested_action IS NULL
        AND actor_user_id IS NULL
        AND system_reason IS NOT DISTINCT FROM 'REVIEW_DEADLINE_PASSED')
      -- 6, 7 an approve, reject or cancel that found the deadline passed
      OR (action = 'EXPIRE' AND decided_by = 'SYSTEM' AND requested_action IS NOT NULL
        AND actor_user_id IS NOT NULL
        AND system_reason IS NOT DISTINCT FROM 'REVIEW_DEADLINE_PASSED'));

-- Transfer idempotency records hold only states the application writes (Q2) --------------------
-- The same four checks as wallet.idempotency_records (V10, Y4), because one engine
-- (common.idempotency) writes both tables from PR 5. Each table stays its module's own (Q2: shared
-- code, not shared records); only the rules on their rows are the same.
ALTER TABLE transfer.idempotency_records
  -- A record has no outcome yet (claim) or a complete one: status and body together.
  ADD CONSTRAINT ck_transfer_idempotency_records_outcome
    CHECK ((response_status IS NULL) = (response_body IS NULL)),
  -- A stored success (2xx) or refusal (4xx, RISK_BLOCKED included); an HTTP status in any case.
  ADD CONSTRAINT ck_transfer_idempotency_records_status
    CHECK (response_status BETWEEN 200 AND 599),
  -- SHA-256 in lower-case hex (doc 01 §7.3).
  ADD CONSTRAINT ck_transfer_idempotency_records_request_hash
    CHECK (request_hash ~ '^[0-9a-f]{64}$'),
  -- A key is kept for a positive time (doc 02 §1.4: 24 h).
  ADD CONSTRAINT ck_transfer_idempotency_records_expiry
    CHECK (expires_at > created_at);
