-- V5: risk module (documentations/06 MVP-ADR-24; doc 07 §1.1). Risk data is Internal and
-- staff-only. transfer_id and customer ids are logical references (no cross-schema FK).

-- One row per sender, locked FOR UPDATE during an assessment, so concurrent requests cannot
-- bypass the transfer-frequency rule.
CREATE TABLE risk.sender_risk_state (
  customer_id uuid        NOT NULL,
  updated_at  timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT pk_sender_risk_state PRIMARY KEY (customer_id)
);

-- Lock rows are created and touched, never deleted (owner, 2026-10-30).
REVOKE DELETE, TRUNCATE ON risk.sender_risk_state FROM ${app_role};

CREATE TABLE risk.risk_assessments (
  id          uuid          NOT NULL DEFAULT gen_random_uuid(),
  -- The transfer id is generated before the assessment (doc 08 Q8).
  transfer_id uuid          NOT NULL,
  customer_id uuid          NOT NULL,
  amount      numeric(19,2) NOT NULL,
  currency    char(3)       NOT NULL,
  -- Sum of the rule weights, capped at 100.
  score       smallint      NOT NULL,
  decision    varchar(20)   NOT NULL,
  assessed_at timestamptz   NOT NULL DEFAULT now(),
  CONSTRAINT pk_risk_assessments PRIMARY KEY (id),
  CONSTRAINT uq_risk_assessments_transfer UNIQUE (transfer_id),
  -- Target of risk_reviews' composite foreign key: a review names its assessment's transfer.
  CONSTRAINT uq_risk_assessments_id_transfer UNIQUE (id, transfer_id),
  CONSTRAINT ck_risk_assessments_amount CHECK (amount > 0),
  CONSTRAINT ck_risk_assessments_currency CHECK (currency = 'THB'),
  CONSTRAINT ck_risk_assessments_score CHECK (score BETWEEN 0 AND 100),
  CONSTRAINT ck_risk_assessments_decision
    CHECK (decision IN ('ALLOW', 'MONITOR', 'MANUAL_REVIEW', 'BLOCK'))
);

-- Frequency rule: the sender's assessments in the last 10 minutes.
CREATE INDEX ix_risk_assessments_customer_assessed
  ON risk.risk_assessments (customer_id, assessed_at);

-- The database sets the time, so the frequency window cannot be dodged by backdating.
CREATE FUNCTION risk.set_assessed_at() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  NEW.assessed_at := now();
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_risk_assessments_assessed_at
  BEFORE INSERT ON risk.risk_assessments
  FOR EACH ROW EXECUTE FUNCTION risk.set_assessed_at();

CREATE TABLE risk.risk_rule_hits (
  assessment_id uuid        NOT NULL,
  rule_code     varchar(40) NOT NULL,
  -- The highest matching weight of the rule.
  weight        smallint    NOT NULL,
  CONSTRAINT pk_risk_rule_hits PRIMARY KEY (assessment_id, rule_code),
  CONSTRAINT fk_risk_rule_hits_assessment
    FOREIGN KEY (assessment_id) REFERENCES risk.risk_assessments (id),
  CONSTRAINT ck_risk_rule_hits_weight CHECK (weight BETWEEN 1 AND 100)
);

-- Review decisions; append-only (MVP-ADR-02 amendment, MVP-ADR-24) --------------------------
CREATE TABLE risk.risk_reviews (
  id              uuid         NOT NULL DEFAULT gen_random_uuid(),
  transfer_id     uuid         NOT NULL,
  assessment_id   uuid         NOT NULL,
  action          varchar(20)  NOT NULL,
  -- Null only for an expiry by the scheduled job.
  actor_user_id   uuid,
  reason_category varchar(40),
  reason          varchar(500),
  created_at      timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_risk_reviews PRIMARY KEY (id),
  -- A held transfer leaves review exactly once.
  CONSTRAINT uq_risk_reviews_transfer UNIQUE (transfer_id),
  -- The review's transfer must be its assessment's transfer.
  CONSTRAINT fk_risk_reviews_assessment
    FOREIGN KEY (assessment_id, transfer_id)
    REFERENCES risk.risk_assessments (id, transfer_id),
  CONSTRAINT ck_risk_reviews_action CHECK (action IN ('APPROVE', 'REJECT', 'CANCEL', 'EXPIRE')),
  CONSTRAINT ck_risk_reviews_actor CHECK (action = 'EXPIRE' OR actor_user_id IS NOT NULL)
);

-- The database sets the time, so a review cannot be backdated.
CREATE FUNCTION risk.set_created_at() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  NEW.created_at := now();
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_risk_reviews_created_at
  BEFORE INSERT ON risk.risk_reviews
  FOR EACH ROW EXECUTE FUNCTION risk.set_created_at();

-- Transfer lookups use the index of uq_risk_reviews_transfer.
CREATE INDEX ix_risk_reviews_assessment ON risk.risk_reviews (assessment_id);

-- Assessments, rule hits and reviews are evidence: append-only (MVP-ADR-02 amendment).
CREATE FUNCTION risk.reject_modification() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  RAISE EXCEPTION '% on %.% is not allowed: the table is append-only',
    TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
    USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_risk_reviews_append_only
  BEFORE UPDATE OR DELETE ON risk.risk_reviews
  FOR EACH ROW EXECUTE FUNCTION risk.reject_modification();

CREATE TRIGGER trg_risk_reviews_no_truncate
  BEFORE TRUNCATE ON risk.risk_reviews
  FOR EACH STATEMENT EXECUTE FUNCTION risk.reject_modification();

REVOKE UPDATE, DELETE, TRUNCATE ON risk.risk_reviews FROM ${app_role};

CREATE TRIGGER trg_risk_assessments_append_only
  BEFORE UPDATE OR DELETE ON risk.risk_assessments
  FOR EACH ROW EXECUTE FUNCTION risk.reject_modification();

CREATE TRIGGER trg_risk_assessments_no_truncate
  BEFORE TRUNCATE ON risk.risk_assessments
  FOR EACH STATEMENT EXECUTE FUNCTION risk.reject_modification();

CREATE TRIGGER trg_risk_rule_hits_append_only
  BEFORE UPDATE OR DELETE ON risk.risk_rule_hits
  FOR EACH ROW EXECUTE FUNCTION risk.reject_modification();

CREATE TRIGGER trg_risk_rule_hits_no_truncate
  BEFORE TRUNCATE ON risk.risk_rule_hits
  FOR EACH STATEMENT EXECUTE FUNCTION risk.reject_modification();

REVOKE UPDATE, DELETE, TRUNCATE ON risk.risk_assessments FROM ${app_role};
REVOKE UPDATE, DELETE, TRUNCATE ON risk.risk_rule_hits FROM ${app_role};

-- Investigations opened by a risk BLOCK (MVP-ADR-24) ------------------------------------------
CREATE TABLE risk.investigations (
  id            uuid         NOT NULL DEFAULT gen_random_uuid(),
  assessment_id uuid         NOT NULL,
  status        varchar(20)  NOT NULL DEFAULT 'OPEN',
  opened_at     timestamptz  NOT NULL DEFAULT now(),
  closed_at     timestamptz,
  -- Closing compliance officer (auth user id).
  closed_by     uuid,
  outcome       varchar(500),
  CONSTRAINT pk_investigations PRIMARY KEY (id),
  CONSTRAINT uq_investigations_assessment UNIQUE (assessment_id),
  CONSTRAINT fk_investigations_assessment
    FOREIGN KEY (assessment_id) REFERENCES risk.risk_assessments (id),
  CONSTRAINT ck_investigations_status CHECK (status IN ('OPEN', 'CLOSED')),
  -- An open investigation has no closing fields. Checked after the BEFORE triggers below, so
  -- closed_at is already the server's time when an investigation closes.
  CONSTRAINT ck_investigations_closed
    CHECK ((status = 'OPEN') = (closed_at IS NULL AND closed_by IS NULL AND outcome IS NULL)),
  -- A closed investigation has its closing time and names the closing officer and the outcome.
  CONSTRAINT ck_investigations_closed_by_outcome
    CHECK (status = 'OPEN' OR (closed_at IS NOT NULL AND closed_by IS NOT NULL AND outcome IS NOT NULL))
);

-- Investigations are closed (updated), never deleted.
REVOKE DELETE, TRUNCATE ON risk.investigations FROM ${app_role};

-- An investigation starts OPEN. The database sets the time, so it cannot be backdated.
CREATE FUNCTION risk.guard_investigation_insert() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM 'OPEN' THEN
    RAISE EXCEPTION 'a new investigation must be OPEN, not %', NEW.status
      USING ERRCODE = 'restrict_violation';
  END IF;

  NEW.opened_at := now();
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_investigations_insert_guard
  BEFORE INSERT ON risk.investigations
  FOR EACH ROW EXECUTE FUNCTION risk.guard_investigation_insert();

-- CLOSED is final: a closed investigation never changes again. An open one may only change its
-- status and closing fields: what it investigates and when it was opened never move. The database
-- sets closed_at when an investigation closes, overriding any value from the client.
CREATE FUNCTION risk.guard_investigation_update() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  IF OLD.status = 'CLOSED' THEN
    RAISE EXCEPTION 'investigation % is CLOSED and can no longer change', OLD.id
      USING ERRCODE = 'restrict_violation';
  END IF;

  IF (NEW.id, NEW.assessment_id, NEW.opened_at) IS DISTINCT FROM
     (OLD.id, OLD.assessment_id, OLD.opened_at) THEN
    RAISE EXCEPTION 'investigation %: id, assessment_id and opened_at are immutable', OLD.id
      USING ERRCODE = 'restrict_violation';
  END IF;

  IF NEW.status = 'CLOSED' THEN
    NEW.closed_at := statement_timestamp();
  END IF;

  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_investigations_update_guard
  BEFORE UPDATE ON risk.investigations
  FOR EACH ROW EXECUTE FUNCTION risk.guard_investigation_update();
