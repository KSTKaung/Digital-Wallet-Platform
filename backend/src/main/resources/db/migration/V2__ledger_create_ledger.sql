-- V2: double-entry ledger (documentations/01 §6; doc 06 MVP-ADR-02, -03, -21; doc 07 §4).
-- Money is numeric(19,2), THB only. The ledger stores no running balance: balances are derived
-- from the entries (doc 07 §4.2). Entries and transactions are append-only.

-- Accounts --------------------------------------------------------------------------------------
CREATE TABLE ledger.ledger_accounts (
  id              uuid        NOT NULL DEFAULT gen_random_uuid(),
  code            varchar(50),
  account_type    varchar(20) NOT NULL,
  -- Customer accounts: the wallet id (logical reference into the wallet schema, no FK).
  owner_reference uuid,
  currency        char(3)     NOT NULL,
  status          varchar(20) NOT NULL DEFAULT 'ACTIVE',
  created_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_ledger_accounts PRIMARY KEY (id),
  CONSTRAINT uq_ledger_accounts_code UNIQUE (code),
  CONSTRAINT ck_ledger_accounts_account_type
    CHECK (account_type IN ('CUSTOMER', 'SYSTEM', 'SUSPENSE')),
  CONSTRAINT ck_ledger_accounts_customer_owner
    CHECK ((account_type = 'CUSTOMER') = (owner_reference IS NOT NULL)),
  CONSTRAINT ck_ledger_accounts_system_code
    CHECK ((account_type <> 'CUSTOMER') = (code IS NOT NULL)),
  CONSTRAINT ck_ledger_accounts_currency CHECK (currency = 'THB'),
  CONSTRAINT ck_ledger_accounts_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED'))
);

-- One ledger account per wallet.
CREATE UNIQUE INDEX uq_ledger_accounts_customer_owner
  ON ledger.ledger_accounts (owner_reference)
  WHERE account_type = 'CUSTOMER';

-- Financial transactions ------------------------------------------------------------------------
CREATE TABLE ledger.financial_transactions (
  id                    uuid         NOT NULL DEFAULT gen_random_uuid(),
  -- The business record's unique reference (doc 07 §4.2 invariant 4).
  transaction_reference varchar(100) NOT NULL,
  transaction_type      varchar(20)  NOT NULL,
  currency              char(3)      NOT NULL,
  correlation_id        varchar(64),
  created_at            timestamptz  NOT NULL DEFAULT now(),
  -- Top-level database transaction that inserted the row; set by the insert trigger.
  created_xid           xid8         NOT NULL,
  CONSTRAINT pk_financial_transactions PRIMARY KEY (id),
  CONSTRAINT uq_financial_transactions_reference UNIQUE (transaction_reference),
  CONSTRAINT ck_financial_transactions_type
    CHECK (transaction_type IN ('DEPOSIT', 'WITHDRAWAL', 'TRANSFER')),
  CONSTRAINT ck_financial_transactions_currency CHECK (currency = 'THB')
);

-- Entries ---------------------------------------------------------------------------------------
CREATE TABLE ledger.ledger_entries (
  id             uuid          NOT NULL DEFAULT gen_random_uuid(),
  transaction_id uuid          NOT NULL,
  account_id     uuid          NOT NULL,
  entry_type     varchar(10)   NOT NULL,
  amount         numeric(19,2) NOT NULL,
  currency       char(3)       NOT NULL,
  created_at     timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT pk_ledger_entries PRIMARY KEY (id),
  CONSTRAINT fk_ledger_entries_transaction
    FOREIGN KEY (transaction_id) REFERENCES ledger.financial_transactions (id),
  CONSTRAINT fk_ledger_entries_account
    FOREIGN KEY (account_id) REFERENCES ledger.ledger_accounts (id),
  CONSTRAINT ck_ledger_entries_entry_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
  CONSTRAINT ck_ledger_entries_amount CHECK (amount > 0),
  CONSTRAINT ck_ledger_entries_currency CHECK (currency = 'THB')
);

CREATE INDEX ix_ledger_entries_transaction ON ledger.ledger_entries (transaction_id);
-- Account history, newest first; also serves the account FK and the derived balance:
-- Σ CREDIT − Σ DEBIT per account (doc 07 §5).
CREATE INDEX ix_ledger_entries_account_created ON ledger.ledger_entries (account_id, created_at, id);

-- Insert guards --------------------------------------------------------------------------------
-- Timestamps are set by the database, so history cannot be backdated.
CREATE FUNCTION ledger.set_created_at() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  NEW.created_at := now();
  RETURN NEW;
END;
$$;

-- Created before the seed rows below, so they are stamped too.
CREATE TRIGGER trg_ledger_accounts_created_at
  BEFORE INSERT ON ledger.ledger_accounts
  FOR EACH ROW EXECUTE FUNCTION ledger.set_created_at();

-- Also records the creating top-level transaction (pg_current_xact_id() returns the top-level id
-- even inside a savepoint); any value sent by the application is overwritten.
CREATE FUNCTION ledger.stamp_financial_transaction() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  NEW.created_at := now();
  NEW.created_xid := pg_current_xact_id();
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_financial_transactions_insert_stamp
  BEFORE INSERT ON ledger.financial_transactions
  FOR EACH ROW EXECUTE FUNCTION ledger.stamp_financial_transaction();

-- An entry may only join a financial transaction created by the current top-level database
-- transaction (created_xid), so a posted (committed) transaction can never gain entries later, not
-- even a balanced pair. Savepoints within that transaction are fine. A header this transaction
-- cannot see (missing, or another session's uncommitted one) is refused the same way: a header
-- created in this transaction is always visible.
-- Entries on FROZEN or CLOSED accounts are rejected (doc 01 §6.5 invariant 6). A customer
-- account's status is read FOR SHARE, so a concurrent freeze or close and the posting serialise:
-- one waits for the other. System and suspense accounts are not locked: every deposit and
-- withdrawal touches SYS-CASH-CLEARING, which would otherwise become a lock hotspot.
CREATE FUNCTION ledger.check_entry_insert() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_created_xid    xid8;
  v_created_at     timestamptz;
  v_account_type   varchar(20);
  v_account_status varchar(20);
BEGIN
  SELECT t.created_xid, t.created_at INTO v_created_xid, v_created_at
    FROM ledger.financial_transactions t
   WHERE t.id = NEW.transaction_id;

  -- created_at too: a logical restore restarts xids, so an old header could match a current xid.
  IF NOT FOUND OR v_created_xid <> pg_current_xact_id() OR v_created_at <> now() THEN
    RAISE EXCEPTION 'ledger transaction % does not belong to this database transaction; entries cannot be added',
      NEW.transaction_id
      USING ERRCODE = 'restrict_violation';
  END IF;

  -- account_type never changes (column privileges), so it can be read without a lock.
  SELECT a.account_type, a.status INTO v_account_type, v_account_status
    FROM ledger.ledger_accounts a
   WHERE a.id = NEW.account_id;

  IF FOUND AND v_account_type = 'CUSTOMER' THEN
    -- Needs UPDATE on at least one column: wallet_app has UPDATE (status).
    SELECT a.status INTO v_account_status
      FROM ledger.ledger_accounts a
     WHERE a.id = NEW.account_id
       FOR SHARE;
  END IF;

  IF FOUND AND v_account_status <> 'ACTIVE' THEN
    RAISE EXCEPTION 'ledger account % is %; it accepts no new entries',
      NEW.account_id, v_account_status
      USING ERRCODE = 'restrict_violation';
  END IF;

  NEW.created_at := now();
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ledger_entries_insert_guard
  BEFORE INSERT ON ledger.ledger_entries
  FOR EACH ROW EXECUTE FUNCTION ledger.check_entry_insert();

-- Balanced-transaction check (doc 01 §6.5; doc 07 §4.2 invariant 1) ---------------------------
-- Deferred constraint triggers run at COMMIT, after all entries of the transaction are inserted.
-- A financial transaction with fewer than 2 entries, or with Σ DEBIT ≠ Σ CREDIT, aborts the commit.
CREATE FUNCTION ledger.check_transaction_balanced() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  v_transaction_id uuid;
  v_entries        bigint;
  v_debit          numeric;
  v_credit         numeric;
BEGIN
  IF TG_TABLE_NAME = 'financial_transactions' THEN
    v_transaction_id := NEW.id;
  ELSE
    v_transaction_id := NEW.transaction_id;
  END IF;

  SELECT count(*),
         coalesce(sum(e.amount) FILTER (WHERE e.entry_type = 'DEBIT'), 0),
         coalesce(sum(e.amount) FILTER (WHERE e.entry_type = 'CREDIT'), 0)
    INTO v_entries, v_debit, v_credit
    FROM ledger.ledger_entries e
   WHERE e.transaction_id = v_transaction_id;

  IF v_entries < 2 THEN
    RAISE EXCEPTION 'ledger transaction % has % entries; at least 2 are required',
      v_transaction_id, v_entries
      USING ERRCODE = 'check_violation';
  END IF;

  IF v_debit <> v_credit THEN
    RAISE EXCEPTION 'ledger transaction % is unbalanced: debit total differs from credit total',
      v_transaction_id
      USING ERRCODE = 'check_violation';
  END IF;

  RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_financial_transactions_balanced
  AFTER INSERT ON ledger.financial_transactions
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION ledger.check_transaction_balanced();

CREATE CONSTRAINT TRIGGER trg_ledger_entries_balanced
  AFTER INSERT ON ledger.ledger_entries
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION ledger.check_transaction_balanced();

-- Append-only (doc 06 MVP-ADR-02, -03) ---------------------------------------------------------
-- Row triggers do not fire on TRUNCATE, hence the separate statement-level guard.
CREATE FUNCTION ledger.reject_modification() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  RAISE EXCEPTION '% on %.% is not allowed: the table is append-only',
    TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
    USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_financial_transactions_append_only
  BEFORE UPDATE OR DELETE ON ledger.financial_transactions
  FOR EACH ROW EXECUTE FUNCTION ledger.reject_modification();

CREATE TRIGGER trg_financial_transactions_no_truncate
  BEFORE TRUNCATE ON ledger.financial_transactions
  FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_modification();

CREATE TRIGGER trg_ledger_entries_append_only
  BEFORE UPDATE OR DELETE ON ledger.ledger_entries
  FOR EACH ROW EXECUTE FUNCTION ledger.reject_modification();

CREATE TRIGGER trg_ledger_entries_no_truncate
  BEFORE TRUNCATE ON ledger.ledger_entries
  FOR EACH STATEMENT EXECUTE FUNCTION ledger.reject_modification();

-- Defence in depth on top of the triggers: the runtime role may only read and insert.
REVOKE UPDATE, DELETE, TRUNCATE ON ledger.financial_transactions FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON ledger.ledger_entries FROM ${app_role};
-- Accounts are never deleted, and their identity (code, type, owner, currency) never changes:
-- only the status may be updated (doc 07 §4.2 invariant 7).
REVOKE UPDATE, DELETE, TRUNCATE ON ledger.ledger_accounts FROM ${app_role};
GRANT UPDATE (status) ON ledger.ledger_accounts TO ${app_role};

-- CLOSED is final: a closed account never changes status again.
CREATE FUNCTION ledger.guard_account_update() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  IF OLD.status = 'CLOSED' AND NEW.status IS DISTINCT FROM OLD.status THEN
    RAISE EXCEPTION 'ledger account % is CLOSED; its status can no longer change', OLD.id
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_ledger_accounts_update_guard
  BEFORE UPDATE ON ledger.ledger_accounts
  FOR EACH ROW EXECUTE FUNCTION ledger.guard_account_update();

-- System accounts (doc 07 D16). No ">= 0" rule applies to them: SYS-CASH-CLEARING goes negative
-- as customers deposit, under the customer sign convention (doc 01 §6.2).
INSERT INTO ledger.ledger_accounts (code, account_type, currency)
VALUES ('SYS-CASH-CLEARING', 'SYSTEM', 'THB'),
       ('SYS-SUSPENSE', 'SUSPENSE', 'THB');
