-- V8: user module, profile and minimal KYC (documentations/06 MVP-ADR-02, -10 amendment, -12, -20;
-- doc 07 §1.1, §9.1; doc 08 §4B). A customer's id is the auth user id (doc 08 Q2): a logical
-- reference, with no foreign key into auth.
--
-- This is a basic KYC review workflow, not identity verification (MVP-ADR-10 amendment). The
-- document reference and the legal name are stored in plain text: masking is not encryption, and
-- field encryption stays a documented gap (MVP-ADR-12).
--
-- Privileges: V1's default privileges grant SELECT, INSERT, UPDATE and DELETE. Neither table is
-- ever deleted from, so DELETE and TRUNCATE are revoked, and UPDATE is granted by column, only for
-- what the profile update, the draft edit, the submit and the approval (PR 4a step 3) write. A
-- profile row is inserted with its user_id only (INSERT is granted on that column alone, J4): the
-- other columns are written later, under the update guard.
--
-- Time columns are set by the database with now(), the transaction's time, so they cannot be
-- backdated and every stamp of one transaction is equal: created_at and updated_at on both tables,
-- and submitted_at and decided_at on applications.

CREATE FUNCTION usr.stamp_insert() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  NEW.created_at := now();
  NEW.updated_at := now();
  RETURN NEW;
END;
$$;

-- Customers -------------------------------------------------------------------------------------
CREATE TABLE usr.customers (
  -- The auth user id (doc 08 Q2).
  user_id       uuid         NOT NULL,
  -- Legal name: null until the approved KYC application is copied here (MVP-ADR-10 amendment).
  first_name    varchar(100),
  last_name     varchar(100),
  date_of_birth date,
  -- ISO 3166-1 alpha-2.
  country_code  char(2),
  created_at    timestamptz  NOT NULL DEFAULT now(),
  updated_at    timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_customers PRIMARY KEY (user_id),
  CONSTRAINT ck_customers_legal_name_pair CHECK ((first_name IS NULL) = (last_name IS NULL)),
  CONSTRAINT ck_customers_legal_name_not_blank
    CHECK (first_name IS NULL OR (btrim(first_name) <> '' AND btrim(last_name) <> '')),
  CONSTRAINT ck_customers_country_code CHECK (country_code ~ '^[A-Z]{2}$')
);

CREATE TRIGGER trg_customers_insert_stamp
  BEFORE INSERT ON usr.customers
  FOR EACH ROW EXECUTE FUNCTION usr.stamp_insert();

-- The legal name is written once, by the approval, and is not editable afterwards (doc 08 Q5). It
-- can only be set in the transaction that approves the customer's application, and only to the
-- name on that application (J4): the approval sets the application APPROVED first, which stamps
-- decided_at with this transaction's now(), and then copies the name, so the check below finds an
-- APPROVED application of this customer decided at now() whose name is the one being written. The
-- date of birth and the country are locked from submit onward (owner, 2026-11-03; MVP-ADR-10
-- amendment): while an application is PENDING_REVIEW or APPROVED, so a reviewer never decides on
-- data that changed after they read it. A REJECTED application unlocks them for correction, and the
-- next submit locks them again. After approval a correction would need its own workflow with
-- compliance review. The lock is read from the applications in the database, never from a client
-- value; a set legal name (copied by the approval) also counts. It holds for every role using
-- normal reads and writes, the owner included; the table owner and superusers can disable triggers.
--
-- Concurrency (owner, 2026-11-04, N4: "DOB and Country cannot be changed after submission, enforced
-- even when multiple requests occur concurrently"). This relies on READ COMMITTED, the PostgreSQL
-- default, which the services also set explicitly on the profile update, the submit and the
-- approval: each statement takes a fresh snapshot, and a statement that waited for a row lock sees
-- what the lock holder committed. A submit takes this row's lock (guard_kyc_update below, FOR
-- SHARE) before its status change; a profile update holds the row (FOR NO KEY UPDATE in the
-- service, then the UPDATE itself) and runs this trigger only once it holds it. So the check below
-- sees a submit that committed while the update waited, and a submit queued behind an update sees
-- the updated row. Under REPEATABLE READ or SERIALIZABLE the waiting statement would fail with a
-- serialization error instead, which is also safe but is not the behaviour tested here.
CREATE FUNCTION usr.guard_customer_update() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  IF (NEW.user_id, NEW.created_at) IS DISTINCT FROM (OLD.user_id, OLD.created_at) THEN
    RAISE EXCEPTION 'usr.customers: user_id and created_at cannot change'
      USING ERRCODE = 'restrict_violation';
  END IF;

  IF OLD.first_name IS NOT NULL
     AND (NEW.first_name, NEW.last_name) IS DISTINCT FROM (OLD.first_name, OLD.last_name) THEN
    RAISE EXCEPTION 'usr.customers: the legal name cannot change once set'
      USING ERRCODE = 'restrict_violation';
  END IF;

  IF OLD.first_name IS NULL AND (NEW.first_name IS NOT NULL OR NEW.last_name IS NOT NULL)
     AND NOT EXISTS (SELECT 1 FROM usr.kyc_applications k
                     WHERE k.customer_id = OLD.user_id
                       AND k.status = 'APPROVED'
                       AND k.decided_at = now()
                       AND k.first_name = NEW.first_name
                       AND k.last_name = NEW.last_name) THEN
    RAISE EXCEPTION 'usr.customers: the legal name is set only by approving its application'
      USING ERRCODE = 'restrict_violation';
  END IF;

  IF (NEW.date_of_birth, NEW.country_code) IS DISTINCT FROM (OLD.date_of_birth, OLD.country_code)
     AND (OLD.first_name IS NOT NULL
          OR EXISTS (SELECT 1 FROM usr.kyc_applications k
                     WHERE k.customer_id = OLD.user_id
                       AND k.status IN ('PENDING_REVIEW', 'APPROVED'))) THEN
    RAISE EXCEPTION 'usr.customers: the profile is locked by a submitted or approved KYC application'
      USING ERRCODE = 'restrict_violation';
  END IF;

  NEW.updated_at := now();
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_customers_update_guard
  BEFORE UPDATE ON usr.customers
  FOR EACH ROW EXECUTE FUNCTION usr.guard_customer_update();

REVOKE INSERT, UPDATE, DELETE, TRUNCATE ON usr.customers FROM ${app_role};
GRANT INSERT (user_id) ON usr.customers TO ${app_role};
GRANT UPDATE (first_name, last_name, date_of_birth, country_code) ON usr.customers TO ${app_role};

-- KYC applications ------------------------------------------------------------------------------
CREATE TABLE usr.kyc_applications (
  id                 uuid         NOT NULL DEFAULT gen_random_uuid(),
  customer_id        uuid         NOT NULL,
  verification_level varchar(20)  NOT NULL,
  document_type      varchar(20)  NOT NULL,
  -- Restricted KYC content: never logged, always returned masked (MVP-ADR-10 amendment).
  document_reference varchar(50)  NOT NULL,
  first_name         varchar(100) NOT NULL,
  last_name          varchar(100) NOT NULL,
  -- The profile's date of birth and country as they were at submit (K4): copied by the submit
  -- trigger from the customer row it holds locked, null for a draft and fixed afterwards. The
  -- reviewer decides on this snapshot, never on the current profile.
  date_of_birth      date,
  country_code       char(2),
  status             varchar(20)  NOT NULL DEFAULT 'DRAFT',
  submitted_at       timestamptz,
  decided_at         timestamptz,
  -- The deciding COMPLIANCE_OFFICER's auth user id; logical reference.
  reviewer_id        uuid,
  -- Shown to the customer (doc 08 Q13).
  rejection_reason   varchar(500),
  created_at         timestamptz  NOT NULL DEFAULT now(),
  updated_at         timestamptz  NOT NULL DEFAULT now(),
  CONSTRAINT pk_kyc_applications PRIMARY KEY (id),
  CONSTRAINT fk_kyc_applications_customer FOREIGN KEY (customer_id)
    REFERENCES usr.customers (user_id),
  CONSTRAINT ck_kyc_applications_verification_level
    CHECK (verification_level IN ('BASIC', 'FULL')),
  CONSTRAINT ck_kyc_applications_document_type CHECK (document_type IN ('ID_CARD', 'PASSPORT')),
  CONSTRAINT ck_kyc_applications_status
    CHECK (status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED')),
  CONSTRAINT ck_kyc_applications_content_not_blank
    CHECK (btrim(document_reference) <> '' AND btrim(first_name) <> '' AND btrim(last_name) <> ''),
  -- Every application but a draft has been submitted, with its profile snapshot.
  CONSTRAINT ck_kyc_applications_submitted CHECK ((status = 'DRAFT') = (submitted_at IS NULL)),
  CONSTRAINT ck_kyc_applications_profile_snapshot
    CHECK ((status = 'DRAFT') = (date_of_birth IS NULL)
           AND (status = 'DRAFT') = (country_code IS NULL)),
  CONSTRAINT ck_kyc_applications_country_code CHECK (country_code ~ '^[A-Z]{2}$'),
  -- A decision has a time and a reviewer; anything undecided has neither, nor a reason.
  CONSTRAINT ck_kyc_applications_decided
    CHECK ((status IN ('APPROVED', 'REJECTED')) = (decided_at IS NOT NULL)
           AND (status IN ('APPROVED', 'REJECTED')) = (reviewer_id IS NOT NULL)),
  CONSTRAINT ck_kyc_applications_rejection_reason
    CHECK ((status = 'REJECTED') = (rejection_reason IS NOT NULL)),
  CONSTRAINT ck_kyc_applications_rejection_reason_not_blank
    CHECK (btrim(rejection_reason) <> ''),
  -- Staff and customer accounts are separate (MVP-ADR-12); this keeps it true here too.
  CONSTRAINT ck_kyc_applications_reviewer_not_customer CHECK (reviewer_id <> customer_id)
);

-- One active application per customer (doc 05 §4); a rejected customer may apply again. This
-- index is also what makes a concurrent second create refuse, through ON CONFLICT DO NOTHING.
CREATE UNIQUE INDEX uq_kyc_applications_active ON usr.kyc_applications (customer_id)
  WHERE status IN ('DRAFT', 'PENDING_REVIEW', 'APPROVED');
-- Staff queue by status, oldest submission first, then id (doc 02 §2.7; K3, P2): the filter and the
-- sort in one index. Drafts are never listed (theirs is the only null submitted_at), so they are
-- left out of the index (N5).
CREATE INDEX ix_kyc_applications_status_submitted
  ON usr.kyc_applications (status, submitted_at, id)
  WHERE status <> 'DRAFT';
-- Owner reads (doc 07 §1.3).
CREATE INDEX ix_kyc_applications_customer_created
  ON usr.kyc_applications (customer_id, created_at);

-- Every application starts as a draft, undecided.
CREATE FUNCTION usr.stamp_kyc_insert() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  IF NEW.status <> 'DRAFT' THEN
    RAISE EXCEPTION 'usr.kyc_applications: an application is created as DRAFT, not %', NEW.status
      USING ERRCODE = 'restrict_violation';
  END IF;
  NEW.created_at := now();
  NEW.updated_at := now();
  NEW.submitted_at := NULL;
  NEW.decided_at := NULL;
  NEW.date_of_birth := NULL;
  NEW.country_code := NULL;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_kyc_applications_insert_stamp
  BEFORE INSERT ON usr.kyc_applications
  FOR EACH ROW EXECUTE FUNCTION usr.stamp_kyc_insert();

-- States: DRAFT -> PENDING_REVIEW -> APPROVED | REJECTED (doc 02 §2.2). A draft may be edited; its
-- content is fixed from submission on, and APPROVED and REJECTED are final. The database records
-- when it was submitted and decided.
--
-- A submit locks the customer's profile row (FOR SHARE) before it changes the status, so it waits
-- for a profile update in flight and a profile update waits for it (guard_customer_update above).
-- The application row is locked first by the UPDATE itself, then the profile: the same order as an
-- approval, so no lock cycle can form. Needs UPDATE on a column of usr.customers, which is granted.
--
-- Under that lock the submit also requires the profile's date of birth and country (J5; the
-- service checks first and gives the 400, this is the backstop) and copies both onto the
-- application (K4). The copy is never written by a client: the columns have no UPDATE grant, and
-- every other update keeps the stored values.
CREATE FUNCTION usr.guard_kyc_update() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
DECLARE
  profile_date_of_birth date;
  profile_country_code  char(2);
  submitting            boolean := OLD.status = 'DRAFT' AND NEW.status = 'PENDING_REVIEW';
BEGIN
  IF submitting THEN
    SELECT c.date_of_birth, c.country_code INTO profile_date_of_birth, profile_country_code
      FROM usr.customers c WHERE c.user_id = NEW.customer_id FOR SHARE;
    IF profile_date_of_birth IS NULL OR profile_country_code IS NULL THEN
      RAISE EXCEPTION 'KYC application % cannot be submitted without a date of birth and a country',
        OLD.id
        USING ERRCODE = 'restrict_violation';
    END IF;
  END IF;

  IF OLD.status IN ('APPROVED', 'REJECTED') THEN
    RAISE EXCEPTION 'KYC application % is % and can no longer change', OLD.id, OLD.status
      USING ERRCODE = 'restrict_violation';
  END IF;

  IF NOT ((OLD.status = 'DRAFT' AND NEW.status IN ('DRAFT', 'PENDING_REVIEW'))
          OR (OLD.status = 'PENDING_REVIEW' AND NEW.status IN ('APPROVED', 'REJECTED'))) THEN
    RAISE EXCEPTION 'KYC application % cannot move from % to %', OLD.id, OLD.status, NEW.status
      USING ERRCODE = 'restrict_violation';
  END IF;

  IF (NEW.id, NEW.customer_id, NEW.created_at)
     IS DISTINCT FROM (OLD.id, OLD.customer_id, OLD.created_at) THEN
    RAISE EXCEPTION 'KYC application % cannot change its identity', OLD.id
      USING ERRCODE = 'restrict_violation';
  END IF;

  IF OLD.status <> 'DRAFT'
     AND (NEW.verification_level, NEW.document_type, NEW.document_reference, NEW.first_name,
          NEW.last_name)
         IS DISTINCT FROM
         (OLD.verification_level, OLD.document_type, OLD.document_reference, OLD.first_name,
          OLD.last_name) THEN
    RAISE EXCEPTION 'KYC application % can only be edited as a DRAFT', OLD.id
      USING ERRCODE = 'restrict_violation';
  END IF;

  NEW.submitted_at := CASE WHEN submitting THEN now() ELSE OLD.submitted_at END;
  NEW.decided_at := CASE
    WHEN NEW.status IN ('APPROVED', 'REJECTED') THEN now()
    ELSE OLD.decided_at END;
  NEW.date_of_birth := CASE WHEN submitting THEN profile_date_of_birth ELSE OLD.date_of_birth END;
  NEW.country_code := CASE WHEN submitting THEN profile_country_code ELSE OLD.country_code END;
  NEW.updated_at := now();
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_kyc_applications_update_guard
  BEFORE UPDATE ON usr.kyc_applications
  FOR EACH ROW EXECUTE FUNCTION usr.guard_kyc_update();

REVOKE UPDATE, DELETE, TRUNCATE ON usr.kyc_applications FROM ${app_role};
GRANT UPDATE (verification_level, document_type, document_reference, first_name, last_name, status,
              reviewer_id, rejection_reason)
  ON usr.kyc_applications TO ${app_role};

-- At commit, an application that became APPROVED has its legal name on the profile (owner,
-- 2026-11-04, N3). The approval sets the application APPROVED and then copies the name; the update
-- guard of usr.customers accepts the name only from that application, and this deferred check makes
-- the copy required: an approval that skipped it fails at commit and rolls back whole. Deferred, so
-- it reads the profile as the transaction left it, after both statements. Holds for every role
-- using normal reads and writes; the table owner and superusers can disable triggers.
CREATE FUNCTION usr.check_approved_legal_name() RETURNS trigger
  LANGUAGE plpgsql
  SET search_path = pg_catalog, pg_temp
AS $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM usr.customers c
                 WHERE c.user_id = NEW.customer_id
                   AND c.first_name = NEW.first_name
                   AND c.last_name = NEW.last_name) THEN
    RAISE EXCEPTION 'KYC application % was approved without copying its legal name', NEW.id
      USING ERRCODE = 'restrict_violation';
  END IF;
  RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_kyc_applications_approved_legal_name
  AFTER UPDATE ON usr.kyc_applications
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW
  WHEN (NEW.status = 'APPROVED' AND OLD.status IS DISTINCT FROM 'APPROVED')
  EXECUTE FUNCTION usr.check_approved_legal_name();
