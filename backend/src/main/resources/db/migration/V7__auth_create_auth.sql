-- V7: auth module (documentations/06 MVP-ADR-05, -06, -14, -23; doc 03 §3.4; doc 08 §4A.3).
-- No plaintext secret columns: passwords are Argon2id hashes, and OTPs and tokens are HMAC-SHA256
-- hex digests. No account is seeded; staff accounts come from a startup runner (doc 08 Q8).
--
-- Privileges: V1's default privileges grant SELECT, INSERT, UPDATE and DELETE, never TRUNCATE. Each
-- table below narrows them. UPDATE is granted by column, so identity and created_at cannot change.
-- DELETE stays only where the purge jobs need it. A user can be deleted only while it is
-- PENDING_VERIFICATION, which a trigger enforces; its credentials go with it by the foreign key, so
-- the runtime role has no DELETE on credentials.
--
-- Time columns: created_at and updated_at are set by the database, so they cannot be backdated, and
-- anything comparing them uses the database's clock. Columns that the policies compare with the
-- application's clock (sent_at, expires_at, locked_until, last_seen_at, last_sent_at) are written by
-- the application, which is what lets the fixed-clock tests of MVP-ADR-23 work.

CREATE FUNCTION auth.set_created_at() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  NEW.created_at := now();
  RETURN NEW;
END;
$$;

CREATE FUNCTION auth.set_updated_at() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END;
$$;

-- Users -----------------------------------------------------------------------------------------
CREATE TABLE auth.users (
  id           uuid         NOT NULL DEFAULT gen_random_uuid(),
  -- Canonical form: the application lower-cases before every read and write.
  email        varchar(254) NOT NULL,
  -- Null only for staff, whose accounts come from environment variables.
  phone_number varchar(16),
  status       varchar(20)  NOT NULL DEFAULT 'PENDING_VERIFICATION',
  role         varchar(20)  NOT NULL,
  created_at   timestamptz  NOT NULL DEFAULT now(),
  updated_at   timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_users PRIMARY KEY (id),
  CONSTRAINT uq_users_email UNIQUE (email),
  CONSTRAINT uq_users_phone_number UNIQUE (phone_number),
  CONSTRAINT ck_users_email CHECK (email = lower(email) AND email LIKE '_%@_%'),
  -- E.164 pattern only: "+" and 8-15 digits (doc 08 Q11).
  CONSTRAINT ck_users_phone_number CHECK (phone_number ~ '^\+[0-9]{8,15}$'),
  CONSTRAINT ck_users_customer_phone CHECK (role <> 'CUSTOMER' OR phone_number IS NOT NULL),
  CONSTRAINT ck_users_status
    CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'FROZEN', 'SUSPENDED')),
  CONSTRAINT ck_users_role CHECK (role IN ('CUSTOMER', 'ADMIN', 'COMPLIANCE_OFFICER'))
);

CREATE TRIGGER trg_users_created_at
  BEFORE INSERT ON auth.users
  FOR EACH ROW EXECUTE FUNCTION auth.set_created_at();

CREATE TRIGGER trg_users_updated_at
  BEFORE INSERT OR UPDATE ON auth.users
  FOR EACH ROW EXECUTE FUNCTION auth.set_updated_at();

-- Only an account nobody ever verified can go: the purge of unverified accounts relies on this, and
-- it holds for every role. Session and reset-token foreign keys still restrict the delete, so the
-- purge leaves out an account that has either.
CREATE FUNCTION auth.refuse_delete_unless_pending() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  IF OLD.status <> 'PENDING_VERIFICATION' THEN
    RAISE EXCEPTION 'auth.users: only a PENDING_VERIFICATION account can be deleted'
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN OLD;
END;
$$;

CREATE TRIGGER trg_users_delete_pending_only
  BEFORE DELETE ON auth.users
  FOR EACH ROW EXECUTE FUNCTION auth.refuse_delete_unless_pending();

-- Purge of unverified accounts.
CREATE INDEX ix_users_pending_created ON auth.users (created_at)
  WHERE status = 'PENDING_VERIFICATION';

-- No UPDATE of email or role: no endpoint or job changes either in the MVP.
REVOKE UPDATE, TRUNCATE ON auth.users FROM ${app_role};
GRANT UPDATE (phone_number, status) ON auth.users TO ${app_role};

-- Credentials: one row per user -----------------------------------------------------------------
CREATE TABLE auth.credentials (
  user_id             uuid        NOT NULL,
  password_hash       text        NOT NULL,
  failed_login_count  integer     NOT NULL DEFAULT 0,
  locked_until        timestamptz,
  password_changed_at timestamptz NOT NULL,
  updated_at          timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_credentials PRIMARY KEY (user_id),
  CONSTRAINT fk_credentials_user FOREIGN KEY (user_id)
    REFERENCES auth.users (id) ON DELETE CASCADE,
  -- Last guard against storing a password that was not hashed.
  CONSTRAINT ck_credentials_password_hash CHECK (password_hash LIKE '$argon2id$%'),
  CONSTRAINT ck_credentials_failed_login_count CHECK (failed_login_count >= 0)
);

CREATE TRIGGER trg_credentials_updated_at
  BEFORE INSERT OR UPDATE ON auth.credentials
  FOR EACH ROW EXECUTE FUNCTION auth.set_updated_at();

REVOKE UPDATE, DELETE, TRUNCATE ON auth.credentials FROM ${app_role};
GRANT UPDATE (password_hash, failed_login_count, locked_until, password_changed_at)
  ON auth.credentials TO ${app_role};

-- Password history: the depth is configuration, so the application deletes rows beyond it --------
CREATE TABLE auth.password_history (
  id            uuid        NOT NULL DEFAULT gen_random_uuid(),
  user_id       uuid        NOT NULL,
  password_hash text        NOT NULL,
  created_at    timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_password_history PRIMARY KEY (id),
  CONSTRAINT fk_password_history_user FOREIGN KEY (user_id)
    REFERENCES auth.users (id) ON DELETE CASCADE,
  CONSTRAINT ck_password_history_password_hash CHECK (password_hash LIKE '$argon2id$%')
);

CREATE INDEX ix_password_history_user_created ON auth.password_history (user_id, created_at);

CREATE TRIGGER trg_password_history_created_at
  BEFORE INSERT ON auth.password_history
  FOR EACH ROW EXECUTE FUNCTION auth.set_created_at();

REVOKE UPDATE, TRUNCATE ON auth.password_history FROM ${app_role};

-- When a registration attempt last emailed an account's owner, so that notice is rate limited ----
CREATE TABLE auth.owner_notices (
  user_id      uuid        NOT NULL,
  last_sent_at timestamptz NOT NULL,
  CONSTRAINT pk_owner_notices PRIMARY KEY (user_id),
  CONSTRAINT fk_owner_notices_user FOREIGN KEY (user_id)
    REFERENCES auth.users (id) ON DELETE CASCADE
);

REVOKE UPDATE, DELETE, TRUNCATE ON auth.owner_notices FROM ${app_role};
GRANT UPDATE (last_sent_at) ON auth.owner_notices TO ${app_role};

-- Failed logins for unknown emails, purged after 24 hours; known accounts use credentials --------
CREATE TABLE auth.login_failure_counters (
  -- HMAC-SHA256 of the canonical email, so a failed login stores no address.
  identifier_hash varchar(64) NOT NULL,
  failed_count    integer     NOT NULL DEFAULT 0,
  locked_until    timestamptz,
  updated_at      timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_login_failure_counters PRIMARY KEY (identifier_hash),
  CONSTRAINT ck_login_failure_counters_identifier_hash
    CHECK (identifier_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_login_failure_counters_failed_count CHECK (failed_count >= 0)
);

CREATE INDEX ix_login_failure_counters_updated ON auth.login_failure_counters (updated_at);

CREATE TRIGGER trg_login_failure_counters_updated_at
  BEFORE INSERT OR UPDATE ON auth.login_failure_counters
  FOR EACH ROW EXECUTE FUNCTION auth.set_updated_at();

REVOKE UPDATE, TRUNCATE ON auth.login_failure_counters FROM ${app_role};
GRANT UPDATE (failed_count, locked_until) ON auth.login_failure_counters TO ${app_role};

-- Sessions: checked on each request through the JWT's sid claim (doc 08 Q6) ----------------------
CREATE TABLE auth.sessions (
  id             uuid        NOT NULL DEFAULT gen_random_uuid(),
  user_id        uuid        NOT NULL,
  created_at     timestamptz NOT NULL DEFAULT now(),
  last_seen_at   timestamptz NOT NULL,
  revoked_at     timestamptz,
  revoked_reason varchar(30),
  CONSTRAINT pk_sessions PRIMARY KEY (id),
  CONSTRAINT fk_sessions_user FOREIGN KEY (user_id) REFERENCES auth.users (id),
  CONSTRAINT ck_sessions_revoked CHECK ((revoked_at IS NULL) = (revoked_reason IS NULL)),
  CONSTRAINT ck_sessions_revoked_reason CHECK (revoked_reason IN (
    'LOGOUT', 'PASSWORD_CHANGE', 'PASSWORD_RESET', 'TOKEN_REUSE', 'IDLE_TIMEOUT'))
);

CREATE INDEX ix_sessions_user ON auth.sessions (user_id);

CREATE TRIGGER trg_sessions_created_at
  BEFORE INSERT ON auth.sessions
  FOR EACH ROW EXECUTE FUNCTION auth.set_created_at();

REVOKE UPDATE, TRUNCATE ON auth.sessions FROM ${app_role};
GRANT UPDATE (last_seen_at, revoked_at, revoked_reason) ON auth.sessions TO ${app_role};

-- Refresh tokens: a session is the token family, and purging it removes its tokens ---------------
CREATE TABLE auth.refresh_tokens (
  id         uuid        NOT NULL DEFAULT gen_random_uuid(),
  session_id uuid        NOT NULL,
  -- HMAC-SHA256 with REFRESH_TOKEN_PEPPER.
  token_hash varchar(64) NOT NULL,
  expires_at timestamptz NOT NULL,
  used_at    timestamptz,
  revoked_at timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
  CONSTRAINT uq_refresh_tokens_token_hash UNIQUE (token_hash),
  CONSTRAINT fk_refresh_tokens_session FOREIGN KEY (session_id)
    REFERENCES auth.sessions (id) ON DELETE CASCADE,
  CONSTRAINT ck_refresh_tokens_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_refresh_tokens_session ON auth.refresh_tokens (session_id);
CREATE INDEX ix_refresh_tokens_expires ON auth.refresh_tokens (expires_at);

CREATE TRIGGER trg_refresh_tokens_created_at
  BEFORE INSERT ON auth.refresh_tokens
  FOR EACH ROW EXECUTE FUNCTION auth.set_created_at();

REVOKE UPDATE, TRUNCATE ON auth.refresh_tokens FROM ${app_role};
GRANT UPDATE (used_at, revoked_at) ON auth.refresh_tokens TO ${app_role};

-- OTP challenges: rows stay after they end, because the limits count them (MVP-ADR-06, -23) ------
CREATE TABLE auth.otp_challenges (
  id              uuid        NOT NULL DEFAULT gen_random_uuid(),
  -- HMAC-SHA256 of the canonical identifier, so the limits also hold for unknown identifiers.
  identifier_hash varchar(64) NOT NULL,
  purpose         varchar(20) NOT NULL,
  -- HMAC-SHA256 with OTP_HMAC_SECRET.
  otp_hash        varchar(64) NOT NULL,
  attempts        integer     NOT NULL DEFAULT 0,
  sent_at         timestamptz NOT NULL,
  expires_at      timestamptz NOT NULL,
  consumed_at     timestamptz,
  -- A resend cancels the previous code.
  cancelled_at    timestamptz,
  -- All attempts spent. These rows count towards the 24-hour used-up cap.
  used_up_at      timestamptz,
  CONSTRAINT pk_otp_challenges PRIMARY KEY (id),
  CONSTRAINT ck_otp_challenges_identifier_hash CHECK (identifier_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_otp_challenges_otp_hash CHECK (otp_hash ~ '^[0-9a-f]{64}$'),
  CONSTRAINT ck_otp_challenges_purpose CHECK (purpose IN ('REGISTRATION', 'PASSWORD_RESET')),
  CONSTRAINT ck_otp_challenges_attempts CHECK (attempts >= 0),
  CONSTRAINT ck_otp_challenges_expiry CHECK (expires_at > sent_at),
  -- A challenge ends in at most one way.
  CONSTRAINT ck_otp_challenges_one_end
    CHECK (num_nonnulls(consumed_at, cancelled_at, used_up_at) <= 1)
);

-- At most one live challenge per identifier and purpose. A challenge that merely expired still
-- counts as live here: a new one must cancel it first, in the same transaction.
CREATE UNIQUE INDEX uq_otp_challenges_live ON auth.otp_challenges (identifier_hash, purpose)
  WHERE consumed_at IS NULL AND cancelled_at IS NULL AND used_up_at IS NULL;
-- Cooldown and hourly resend count.
CREATE INDEX ix_otp_challenges_identifier_sent
  ON auth.otp_challenges (identifier_hash, purpose, sent_at);
-- 24-hour used-up cap, per identifier across purposes.
CREATE INDEX ix_otp_challenges_identifier_used_up
  ON auth.otp_challenges (identifier_hash, used_up_at) WHERE used_up_at IS NOT NULL;
-- Purge.
CREATE INDEX ix_otp_challenges_sent ON auth.otp_challenges (sent_at);

REVOKE UPDATE, TRUNCATE ON auth.otp_challenges FROM ${app_role};
GRANT UPDATE (attempts, consumed_at, cancelled_at, used_up_at) ON auth.otp_challenges TO ${app_role};

-- Password-reset tokens: single-use, stored as a hash (MVP-ADR-23) -------------------------------
CREATE TABLE auth.password_reset_tokens (
  id         uuid        NOT NULL DEFAULT gen_random_uuid(),
  user_id    uuid        NOT NULL,
  -- Hex digest, like the refresh token's hash.
  token_hash varchar(64) NOT NULL,
  expires_at timestamptz NOT NULL,
  used_at    timestamptz,
  created_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_password_reset_tokens PRIMARY KEY (id),
  CONSTRAINT uq_password_reset_tokens_token_hash UNIQUE (token_hash),
  CONSTRAINT fk_password_reset_tokens_user FOREIGN KEY (user_id) REFERENCES auth.users (id),
  CONSTRAINT ck_password_reset_tokens_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX ix_password_reset_tokens_user ON auth.password_reset_tokens (user_id);
CREATE INDEX ix_password_reset_tokens_expires ON auth.password_reset_tokens (expires_at);

CREATE TRIGGER trg_password_reset_tokens_created_at
  BEFORE INSERT ON auth.password_reset_tokens
  FOR EACH ROW EXECUTE FUNCTION auth.set_created_at();

REVOKE UPDATE, TRUNCATE ON auth.password_reset_tokens FROM ${app_role};
GRANT UPDATE (used_at) ON auth.password_reset_tokens TO ${app_role};
