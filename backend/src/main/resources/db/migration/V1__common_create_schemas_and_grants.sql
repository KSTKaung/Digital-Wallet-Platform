-- V1: one schema per module (documentations/06 MVP-ADR-02, -24; doc 07 §1.1).
-- Runs as the migration owner (wallet_owner), which therefore owns every schema and table.
-- The runtime role ${app_role} gets USAGE and DML only: it owns nothing, so it cannot ALTER a
-- table or DISABLE TRIGGER (MVP-ADR-02 amendment, doc 07 D19).
-- auth, usr and notification stay empty until PR 3 and PR 4 (doc 08 Q1).

CREATE SCHEMA auth;
CREATE SCHEMA usr;
CREATE SCHEMA wallet;
CREATE SCHEMA transfer;
CREATE SCHEMA ledger;
CREATE SCHEMA risk;
CREATE SCHEMA audit;
CREATE SCHEMA notification;

GRANT USAGE ON SCHEMA auth, usr, wallet, transfer, ledger, risk, audit, notification TO ${app_role};

-- Default privileges apply to objects that the current role (wallet_owner) creates later, so
-- tables from later migrations are granted automatically. Append-only tables revoke UPDATE and
-- DELETE again in the migration that creates them.
ALTER DEFAULT PRIVILEGES IN SCHEMA auth, usr, wallet, transfer, ledger, risk, audit, notification
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO ${app_role};

ALTER DEFAULT PRIVILEGES IN SCHEMA auth, usr, wallet, transfer, ledger, risk, audit, notification
  GRANT USAGE, SELECT ON SEQUENCES TO ${app_role};

-- Functions created later by wallet_owner are not executable by PUBLIC (and so not by
-- ${app_role}). Only the global form (no IN SCHEMA) can revoke the built-in PUBLIC EXECUTE default.
-- Triggers still fire for ${app_role}: trigger execution does not check EXECUTE.
ALTER DEFAULT PRIVILEGES REVOKE EXECUTE ON FUNCTIONS FROM PUBLIC;
