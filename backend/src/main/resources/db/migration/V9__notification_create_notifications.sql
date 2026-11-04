-- V9: notification module, the email outbox for business notifications (documentations/06
-- MVP-ADR-25; doc 01 §7.2 notification.notifications). No Kafka: a row is written in the business
-- transaction it reports and is sent after commit by an in-process job.
--
-- A row holds the recipient's user id, never an email address: the address is looked up when the
-- row is sent. The payload holds the template values only, never KYC content, secrets or the
-- address. OTP, registration and password-reset emails are not here; they stay direct (MVP-ADR-25).
--
-- Privileges: V1's default privileges grant SELECT, INSERT, UPDATE and DELETE. Rows are never
-- deleted (SENT and FAILED rows are kept; no purge in the MVP), so DELETE and TRUNCATE are revoked,
-- and UPDATE is granted only on the columns the dispatcher writes.
--
-- Time columns: occurred_at (the time of the change, now() of the business transaction, the same
-- value as the change's own database stamps), sent_at and updated_at are set by the database.
-- next_attempt_at is compared with the application's clock and written by the application, as in
-- V7, so the retry schedule and the claim lease follow one clock and can be tested with a fixed one
-- (N2): the insert takes the value the outbox supplies (the default now() only if none is given).
-- occurred_at stays the database's now(), because it must equal the change's own stamps (for
-- example the KYC decided_at), which the database sets too.

CREATE TABLE notification.notifications (
  -- The stable event id (MVP-ADR-25): generated once, never reused, never changed.
  event_id        uuid         NOT NULL,
  -- The recipient's auth user id; logical reference, no foreign key into auth.
  user_id         uuid         NOT NULL,
  kind            varchar(40)  NOT NULL,
  -- Template values; an object of strings, possibly empty.
  payload         jsonb        NOT NULL DEFAULT '{}'::jsonb,
  status          varchar(20)  NOT NULL DEFAULT 'PENDING',
  attempts        integer      NOT NULL DEFAULT 0,
  next_attempt_at timestamptz  NOT NULL DEFAULT now(),
  -- The failure's class name of the last attempt; never an exception message, which can quote the
  -- address.
  last_failure    varchar(100),
  occurred_at     timestamptz  NOT NULL DEFAULT now(),
  sent_at         timestamptz,
  updated_at      timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_notifications PRIMARY KEY (event_id),
  -- Only the business emails of MVP-ADR-25. A later kind needs a migration that adds the new
  -- constraint with NOT VALID (no table scan under a heavy lock) and then runs VALIDATE CONSTRAINT,
  -- before dropping this one (N6).
  CONSTRAINT ck_notifications_kind
    CHECK (kind IN ('KYC_APPROVED', 'KYC_REJECTED', 'WALLET_FROZEN', 'WALLET_UNFROZEN')),
  CONSTRAINT ck_notifications_status CHECK (status IN ('PENDING', 'SENT', 'FAILED')),
  CONSTRAINT ck_notifications_attempts CHECK (attempts >= 0),
  CONSTRAINT ck_notifications_payload_object CHECK (jsonb_typeof(payload) = 'object'),
  CONSTRAINT ck_notifications_sent CHECK ((status = 'SENT') = (sent_at IS NOT NULL))
);

-- The dispatcher's claim: due PENDING rows, oldest due first. SENT and FAILED rows, which are kept
-- for good, stay out of the index.
CREATE INDEX ix_notifications_pending_due
  ON notification.notifications (next_attempt_at, event_id)
  WHERE status = 'PENDING';

-- A row starts PENDING with no attempt; the database records when the change happened.
CREATE FUNCTION notification.stamp_insert() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  IF NEW.status <> 'PENDING' OR NEW.attempts <> 0 THEN
    RAISE EXCEPTION 'notification.notifications: a row is created PENDING with no attempt'
      USING ERRCODE = 'restrict_violation';
  END IF;
  NEW.occurred_at := now();
  NEW.updated_at := now();
  NEW.sent_at := NULL;
  NEW.last_failure := NULL;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_notifications_insert_stamp
  BEFORE INSERT ON notification.notifications
  FOR EACH ROW EXECUTE FUNCTION notification.stamp_insert();

-- What was reported never changes: event id, recipient, kind, payload and time of the change. The
-- status moves only PENDING -> SENT | FAILED, and both are final, so a row that is SENT or FAILED is
-- never sent again. Attempts only grow; each claim adds one (MVP-ADR-25, R1), and the claim never
-- goes past the configured maximum, which is configuration and so is not a CHECK here. Holds for
-- every role using normal reads and writes; the table owner and superusers can disable triggers.
CREATE FUNCTION notification.guard_update() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  IF (NEW.event_id, NEW.user_id, NEW.kind, NEW.payload, NEW.occurred_at)
     IS DISTINCT FROM (OLD.event_id, OLD.user_id, OLD.kind, OLD.payload, OLD.occurred_at) THEN
    RAISE EXCEPTION 'notification %: event id, recipient, kind, payload and time cannot change',
      OLD.event_id
      USING ERRCODE = 'restrict_violation';
  END IF;

  IF OLD.status <> 'PENDING' THEN
    RAISE EXCEPTION 'notification % is % and can no longer change', OLD.event_id, OLD.status
      USING ERRCODE = 'restrict_violation';
  END IF;

  IF NEW.attempts < OLD.attempts THEN
    RAISE EXCEPTION 'notification %: attempts cannot decrease', OLD.event_id
      USING ERRCODE = 'restrict_violation';
  END IF;

  NEW.sent_at := CASE WHEN NEW.status = 'SENT' THEN now() END;
  NEW.updated_at := now();
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_notifications_update_guard
  BEFORE UPDATE ON notification.notifications
  FOR EACH ROW EXECUTE FUNCTION notification.guard_update();

REVOKE UPDATE, DELETE, TRUNCATE ON notification.notifications FROM ${app_role};
GRANT UPDATE (status, attempts, next_attempt_at, last_failure)
  ON notification.notifications TO ${app_role};
