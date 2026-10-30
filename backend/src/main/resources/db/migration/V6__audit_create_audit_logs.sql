-- V6: audit log (documentations/03 §7.2; doc 06 MVP-ADR-02 amendment; doc 07 §9.3).
-- Append-only. old_value/new_value never contain hashes, tokens, OTPs or KYC content.

CREATE TABLE audit.audit_logs (
  id             bigint       GENERATED ALWAYS AS IDENTITY,
  occurred_at    timestamptz  NOT NULL DEFAULT now(),
  -- Auth user id; null for anonymous or system actions (logical reference, no FK).
  actor_id       uuid,
  actor_type     varchar(20)  NOT NULL,
  action         varchar(60)  NOT NULL,
  resource       varchar(60)  NOT NULL,
  resource_id    varchar(64),
  result         varchar(20)  NOT NULL,
  client_ip      inet,
  -- No device source in the MVP (doc 07 §9.3).
  device         varchar(200),
  correlation_id varchar(64),
  old_value      jsonb,
  new_value      jsonb,
  CONSTRAINT pk_audit_logs PRIMARY KEY (id),
  CONSTRAINT ck_audit_logs_actor_type
    CHECK (actor_type IN ('CUSTOMER', 'STAFF', 'SYSTEM', 'ANONYMOUS')),
  CONSTRAINT ck_audit_logs_result CHECK (result IN ('SUCCESS', 'FAILURE', 'DENIED'))
);

CREATE INDEX ix_audit_logs_actor_occurred ON audit.audit_logs (actor_id, occurred_at);
CREATE INDEX ix_audit_logs_resource ON audit.audit_logs (resource, resource_id);

-- The database sets the time, so audit history cannot be backdated.
CREATE FUNCTION audit.set_occurred_at() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  NEW.occurred_at := now();
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_audit_logs_occurred_at
  BEFORE INSERT ON audit.audit_logs
  FOR EACH ROW EXECUTE FUNCTION audit.set_occurred_at();

CREATE FUNCTION audit.reject_modification() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  RAISE EXCEPTION '% on %.% is not allowed: the table is append-only',
    TG_OP, TG_TABLE_SCHEMA, TG_TABLE_NAME
    USING ERRCODE = 'restrict_violation';
END;
$$;

CREATE TRIGGER trg_audit_logs_append_only
  BEFORE UPDATE OR DELETE ON audit.audit_logs
  FOR EACH ROW EXECUTE FUNCTION audit.reject_modification();

CREATE TRIGGER trg_audit_logs_no_truncate
  BEFORE TRUNCATE ON audit.audit_logs
  FOR EACH STATEMENT EXECUTE FUNCTION audit.reject_modification();

REVOKE UPDATE, DELETE, TRUNCATE ON audit.audit_logs FROM ${app_role};
