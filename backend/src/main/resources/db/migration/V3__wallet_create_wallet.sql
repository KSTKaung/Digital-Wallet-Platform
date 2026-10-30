-- V3: wallet module (documentations/01 §7.3, §7.4; doc 06 MVP-ADR-08, -10, -13, -18, -20, -21, -22;
-- doc 07 §1.1, §3, §7). customer_id is a logical reference into usr (no cross-schema FK).

-- Wallets ---------------------------------------------------------------------------------------
CREATE TABLE wallet.wallets (
  id                uuid          NOT NULL DEFAULT gen_random_uuid(),
  -- Public number: 10 random digits plus a Luhn check digit (doc 08 Q3); immutable (trigger).
  wallet_number     char(11)      NOT NULL,
  customer_id       uuid          NOT NULL,
  tier              varchar(20)   NOT NULL,
  status            varchar(20)   NOT NULL DEFAULT 'ACTIVE',
  currency          char(3)       NOT NULL,
  available_balance numeric(19,2) NOT NULL DEFAULT 0,
  -- Amounts held for transfers under review (MVP-ADR-24).
  reserved_balance  numeric(19,2) NOT NULL DEFAULT 0,
  version           bigint        NOT NULL DEFAULT 0,
  created_at        timestamptz   NOT NULL DEFAULT now(),
  updated_at        timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT pk_wallets PRIMARY KEY (id),
  CONSTRAINT uq_wallets_wallet_number UNIQUE (wallet_number),
  -- One wallet per customer; also makes a concurrent double KYC approval safe (doc 01 §7.3).
  CONSTRAINT uq_wallets_customer UNIQUE (customer_id),
  -- Target of the composite foreign keys that tie a money record to the wallet's own customer.
  CONSTRAINT uq_wallets_id_customer UNIQUE (id, customer_id),
  CONSTRAINT ck_wallets_wallet_number CHECK (wallet_number ~ '^[0-9]{11}$'),
  CONSTRAINT ck_wallets_tier CHECK (tier IN ('BASIC', 'VERIFIED')),
  CONSTRAINT ck_wallets_status CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
  CONSTRAINT ck_wallets_currency CHECK (currency = 'THB'),
  -- Last guard against overdraft (MVP-ADR-08).
  CONSTRAINT ck_wallets_available_balance CHECK (available_balance >= 0),
  CONSTRAINT ck_wallets_reserved_balance CHECK (reserved_balance >= 0)
);

-- Timestamps are set by the database, so a wallet's history cannot be backdated.
CREATE FUNCTION wallet.stamp_wallet_insert() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  NEW.created_at := now();
  NEW.updated_at := now();
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_wallets_insert_stamp
  BEFORE INSERT ON wallet.wallets
  FOR EACH ROW EXECUTE FUNCTION wallet.stamp_wallet_insert();

-- A wallet's identity never changes, CLOSED is final, and updated_at is set by the database.
CREATE FUNCTION wallet.guard_wallet_update() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  IF (NEW.id, NEW.wallet_number, NEW.customer_id, NEW.currency, NEW.created_at)
     IS DISTINCT FROM
     (OLD.id, OLD.wallet_number, OLD.customer_id, OLD.currency, OLD.created_at) THEN
    RAISE EXCEPTION 'wallet %: id, wallet_number, customer_id, currency and created_at are immutable',
      OLD.id
      USING ERRCODE = 'restrict_violation';
  END IF;

  IF OLD.status = 'CLOSED' AND NEW.status IS DISTINCT FROM OLD.status THEN
    RAISE EXCEPTION 'wallet % is CLOSED; its status can no longer change', OLD.id
      USING ERRCODE = 'restrict_violation';
  END IF;

  NEW.updated_at := now();
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_wallets_update_guard
  BEFORE UPDATE ON wallet.wallets
  FOR EACH ROW EXECUTE FUNCTION wallet.guard_wallet_update();

-- Deposits and withdrawals (simulated, synchronous: status COMPLETED only; doc 07 D10) --------
CREATE TABLE wallet.deposits (
  id               uuid          NOT NULL DEFAULT gen_random_uuid(),
  wallet_id        uuid          NOT NULL,
  customer_id      uuid          NOT NULL,
  amount           numeric(19,2) NOT NULL,
  currency         char(3)       NOT NULL,
  status           varchar(20)   NOT NULL,
  idempotency_key  uuid          NOT NULL,
  -- Server-generated unique reference; also the ledger transaction_reference.
  reference        varchar(40)   NOT NULL,
  -- Client "reference" from the request: free-text note, not unique (doc 07 D10).
  client_reference varchar(100),
  -- Asia/Bangkok business day the daily limit was counted on (MVP-ADR-22).
  limit_date       date          NOT NULL,
  -- Always POSTED in the MVP: posting is atomic with this row (MVP-ADR-03; doc 07 §6).
  ledger_status    varchar(20)   NOT NULL DEFAULT 'POSTED',
  correlation_id   varchar(64),
  created_at       timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT pk_deposits PRIMARY KEY (id),
  CONSTRAINT uq_deposits_reference UNIQUE (reference),
  -- Keys are scoped per customer, so another customer's key never collides (doc 07 §7).
  CONSTRAINT uq_deposits_customer_idempotency_key UNIQUE (customer_id, idempotency_key),
  -- The deposit's customer must be the wallet's customer.
  CONSTRAINT fk_deposits_wallet FOREIGN KEY (wallet_id, customer_id)
    REFERENCES wallet.wallets (id, customer_id),
  CONSTRAINT ck_deposits_amount CHECK (amount > 0),
  CONSTRAINT ck_deposits_currency CHECK (currency = 'THB'),
  CONSTRAINT ck_deposits_status CHECK (status IN ('COMPLETED')),
  CONSTRAINT ck_deposits_ledger_status CHECK (ledger_status = 'POSTED')
);

CREATE INDEX ix_deposits_wallet_created ON wallet.deposits (wallet_id, created_at);
CREATE INDEX ix_deposits_customer_created ON wallet.deposits (customer_id, created_at);

CREATE TABLE wallet.withdrawals (
  id             uuid          NOT NULL DEFAULT gen_random_uuid(),
  wallet_id      uuid          NOT NULL,
  customer_id    uuid          NOT NULL,
  amount         numeric(19,2) NOT NULL,
  currency       char(3)       NOT NULL,
  status          varchar(20)   NOT NULL,
  idempotency_key uuid          NOT NULL,
  reference       varchar(40)   NOT NULL,
  limit_date      date          NOT NULL,
  ledger_status   varchar(20)   NOT NULL DEFAULT 'POSTED',
  correlation_id  varchar(64),
  created_at      timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT pk_withdrawals PRIMARY KEY (id),
  CONSTRAINT uq_withdrawals_reference UNIQUE (reference),
  CONSTRAINT uq_withdrawals_customer_idempotency_key UNIQUE (customer_id, idempotency_key),
  CONSTRAINT fk_withdrawals_wallet FOREIGN KEY (wallet_id, customer_id)
    REFERENCES wallet.wallets (id, customer_id),
  CONSTRAINT ck_withdrawals_amount CHECK (amount > 0),
  CONSTRAINT ck_withdrawals_currency CHECK (currency = 'THB'),
  CONSTRAINT ck_withdrawals_status CHECK (status IN ('COMPLETED')),
  CONSTRAINT ck_withdrawals_ledger_status CHECK (ledger_status = 'POSTED')
);

CREATE INDEX ix_withdrawals_wallet_created ON wallet.withdrawals (wallet_id, created_at);
CREATE INDEX ix_withdrawals_customer_created ON wallet.withdrawals (customer_id, created_at);

-- Completed money records never change and are never deleted (doc 05 §4; MVP-ADR-02 amendment).
CREATE FUNCTION wallet.reject_modification() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  RAISE EXCEPTION '% on %.% is not allowed: the table is append-only',
    TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
    USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_deposits_append_only
  BEFORE UPDATE OR DELETE ON wallet.deposits
  FOR EACH ROW EXECUTE FUNCTION wallet.reject_modification();

CREATE TRIGGER trg_deposits_no_truncate
  BEFORE TRUNCATE ON wallet.deposits
  FOR EACH STATEMENT EXECUTE FUNCTION wallet.reject_modification();

CREATE TRIGGER trg_withdrawals_append_only
  BEFORE UPDATE OR DELETE ON wallet.withdrawals
  FOR EACH ROW EXECUTE FUNCTION wallet.reject_modification();

CREATE TRIGGER trg_withdrawals_no_truncate
  BEFORE TRUNCATE ON wallet.withdrawals
  FOR EACH STATEMENT EXECUTE FUNCTION wallet.reject_modification();

REVOKE UPDATE, DELETE, TRUNCATE ON wallet.deposits FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON wallet.withdrawals FROM ${app_role};

-- Timestamps are set by the database, so history cannot be backdated.
CREATE FUNCTION wallet.set_created_at() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  NEW.created_at := now();
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_deposits_created_at
  BEFORE INSERT ON wallet.deposits
  FOR EACH ROW EXECUTE FUNCTION wallet.set_created_at();

CREATE TRIGGER trg_withdrawals_created_at
  BEFORE INSERT ON wallet.withdrawals
  FOR EACH ROW EXECUTE FUNCTION wallet.set_created_at();

-- Daily limit usage per wallet, operation and Asia/Bangkok day (MVP-ADR-18, -22) ---------------
-- Locked FOR UPDATE together with the wallet. Limits themselves come from configuration.
CREATE TABLE wallet.daily_limit_usage (
  wallet_id  uuid          NOT NULL,
  operation  varchar(20)   NOT NULL,
  limit_date date          NOT NULL,
  -- COMPLETED movements.
  used       numeric(19,2) NOT NULL DEFAULT 0,
  -- Amounts held by transfers in PENDING_REVIEW (MVP-ADR-24).
  reserved   numeric(19,2) NOT NULL DEFAULT 0,
  version    bigint        NOT NULL DEFAULT 0,
  CONSTRAINT pk_daily_limit_usage PRIMARY KEY (wallet_id, operation, limit_date),
  CONSTRAINT fk_daily_limit_usage_wallet FOREIGN KEY (wallet_id) REFERENCES wallet.wallets (id),
  CONSTRAINT ck_daily_limit_usage_operation
    CHECK (operation IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL')),
  CONSTRAINT ck_daily_limit_usage_used CHECK (used >= 0),
  CONSTRAINT ck_daily_limit_usage_reserved CHECK (reserved >= 0)
);

-- Wallets and limit usage are updated in place but never deleted. Idempotency records keep DELETE
-- for their purge job (doc 07 D04); lookup attempts are revoked separately below.
REVOKE DELETE, TRUNCATE ON wallet.wallets FROM ${app_role};
REVOKE DELETE, TRUNCATE ON wallet.daily_limit_usage FROM ${app_role};

-- Idempotency for deposits and withdrawals (doc 02 §1.4; doc 06 MVP-ADR-06; doc 07 D02, D03) --
CREATE TABLE wallet.idempotency_records (
  customer_id     uuid        NOT NULL,
  operation       varchar(20) NOT NULL,
  idempotency_key uuid        NOT NULL,
  -- SHA-256 of the canonical validated request (doc 01 §7.3).
  request_hash    varchar(64) NOT NULL,
  -- Stored outcome, replayed for the same key and body.
  response_status smallint,
  response_body   jsonb,
  created_at      timestamptz NOT NULL DEFAULT now(),
  expires_at      timestamptz NOT NULL,
  CONSTRAINT pk_wallet_idempotency_records PRIMARY KEY (customer_id, operation, idempotency_key),
  CONSTRAINT ck_wallet_idempotency_records_operation
    CHECK (operation IN ('DEPOSIT', 'WITHDRAWAL'))
);

-- The purge job deletes only expired keys (doc 07 D04).
CREATE INDEX ix_wallet_idempotency_records_expires ON wallet.idempotency_records (expires_at);

-- Wallet-number lookup attempts: sliding-window rate limits (MVP-ADR-20) ----------------------
CREATE TABLE wallet.lookup_attempts (
  id           bigint      GENERATED ALWAYS AS IDENTITY,
  user_id      uuid        NOT NULL,
  client_ip    inet        NOT NULL,
  attempted_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_lookup_attempts PRIMARY KEY (id)
);

CREATE INDEX ix_lookup_attempts_user_attempted ON wallet.lookup_attempts (user_id, attempted_at);
CREATE INDEX ix_lookup_attempts_ip_attempted ON wallet.lookup_attempts (client_ip, attempted_at);
-- Purge job: deletes old attempts by time (MVP-ADR-20).
CREATE INDEX ix_lookup_attempts_attempted ON wallet.lookup_attempts (attempted_at);

-- Attempts are recorded and purged, never changed: DELETE stays for the purge job (MVP-ADR-20).
REVOKE UPDATE, TRUNCATE ON wallet.lookup_attempts FROM ${app_role};

-- The database sets the time, so a caller cannot backdate an attempt out of the window.
CREATE FUNCTION wallet.set_attempted_at() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  NEW.attempted_at := now();
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_lookup_attempts_attempted_at
  BEFORE INSERT ON wallet.lookup_attempts
  FOR EACH ROW EXECUTE FUNCTION wallet.set_attempted_at();
