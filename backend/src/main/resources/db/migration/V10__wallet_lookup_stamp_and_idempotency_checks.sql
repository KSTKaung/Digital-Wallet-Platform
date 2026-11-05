-- V10: wallet module, PR 4b step 11 review (owner, 2026-11-04, Y1 and Y4). V1–V9 are unchanged.

-- Y1: lookup attempts are stamped with the start of the INSERT statement, not of the transaction --
-- A lookup takes its advisory locks in statements of their own and may wait there. now() is the
-- transaction's start, so an attempt stamped with it, and windows measured with it, would lag by
-- the lock wait. statement_timestamp() is the start of the current statement: the INSERT, like the
-- window counts, runs after the locks are held, so it records the time after the wait. CREATE OR
-- REPLACE keeps the trigger, the owner and the privileges (not executable by PUBLIC, V1); the
-- function keeps its pinned search_path and stays SECURITY INVOKER.
CREATE OR REPLACE FUNCTION wallet.set_attempted_at() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  NEW.attempted_at := statement_timestamp();
  RETURN NEW;
END;
$$;

-- Y4: idempotency records hold only states the application writes ------------------------------
-- One ALTER TABLE, so one lock and one scan of the existing rows (owner, 2026-11-05, AA1). Adding a
-- CHECK takes an ACCESS EXCLUSIVE lock on the table for the length of the scan, NOT VALID or not,
-- and Flyway runs this file in one transaction, so a NOT VALID / VALIDATE split would gain nothing.
-- The lock is short and accepted: the table holds at most 24 h of keys (doc 02 §1.4), purged
-- hourly. A lock_timeout for migrations is a doc 07 D14 decision.
ALTER TABLE wallet.idempotency_records
  -- A record has no outcome yet (claim) or a complete one: status and body together.
  ADD CONSTRAINT ck_wallet_idempotency_records_outcome
    CHECK ((response_status IS NULL) = (response_body IS NULL)),
  -- A stored success (2xx) or refusal (4xx); an HTTP status in any case.
  ADD CONSTRAINT ck_wallet_idempotency_records_status
    CHECK (response_status BETWEEN 200 AND 599),
  -- SHA-256 in lower-case hex (doc 01 §7.3; RequestFingerprint).
  ADD CONSTRAINT ck_wallet_idempotency_records_request_hash
    CHECK (request_hash ~ '^[0-9a-f]{64}$'),
  -- A key is kept for a positive time (doc 02 §1.4: 24 h; IdempotencyProperties refuses < 1 s).
  ADD CONSTRAINT ck_wallet_idempotency_records_expiry
    CHECK (expires_at > created_at);
