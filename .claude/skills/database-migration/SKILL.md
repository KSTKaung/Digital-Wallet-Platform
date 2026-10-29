---
name: database-migration
description: Workflow for PostgreSQL schema changes with Flyway in the modular wallet application (one database, one schema per module, one Flyway stream) — inspect the current schema and migration history, plan a backward-compatible (expand → migrate → contract) change, write a new migration, review constraints and indexes, and test it with Testcontainers. Use whenever a task adds or changes schemas, tables, columns, constraints, indexes, triggers, seed data or grants.
argument-hint: "<module> <change description>"
---

# Database migration (PostgreSQL + Flyway)

## Prerequisites
- Identify the owning module and its schema (`auth`, `usr`, `wallet`, `transfer`, `ledger`, `audit`, `notification`; doc 06 MVP-ADR-02). A module's tables live only in its own schema. No foreign keys or joins across schemas.
- Migrations live in `backend/src/main/resources/db/migration` (one Flyway stream for the whole database).
- Read the target design: `documentations/01. HighLevelArchitecture.md` §7 (tables §7.2, schema delta §7.3, constraints §7.4, indexing §7.7) and doc 06 for MVP deviations (e.g. ledger immutability trigger, MVP-ADR-03).

## Workflow

### 1. Inspect
- List existing migrations and the latest version; read the ones touching the affected tables.
- File naming: `V<n>__<module>_<description>.sql` (e.g. `V3__wallet_create_wallets.sql`), continuing the existing sequence. Follow any established scheme exactly.
- Check whether doc 01 §7.3 already defines the change; use its names and types where they fit the MVP.

### 2. Plan a compatible change
- Use expand → migrate → contract: add nullable/defaulted columns or new tables first, backfill separately, switch code, and only drop/rename in a later release.
- A migration must work with both the currently deployed code and the new code.
- These need explicit user approval before writing: dropping or renaming columns/tables/schemas, narrowing types, deleting data, changing the ledger immutability trigger or grants, long-locking operations on hot tables (`wallet.wallets`, `transfer.transfers`, `ledger.ledger_entries`).

### 3. Write the migration
- **New file only.** Never edit a migration that has been merged (append-only).
- Conventions:
  - money `numeric(19,4)` with `CHECK (amount > 0)` where applicable; currency `char(3)`;
  - ids `uuid DEFAULT gen_random_uuid()`; timestamps `timestamptz NOT NULL DEFAULT now()`;
  - status columns `varchar(20)` with a `CHECK (... IN (...))` listing documented values;
  - balances `CHECK (... >= 0)`; `version bigint NOT NULL DEFAULT 0` on wallets;
  - unique constraints for business references and idempotency keys;
  - schema-qualified names (`wallet.wallets`).
- Financial records are never physically deleted; no `DELETE` of financial data in migrations.
- Ledger tables are append-only: a trigger rejects `UPDATE`/`DELETE` on `ledger.ledger_entries` and `ledger.financial_transactions`. System ledger accounts are seeded by migration.
- Create indexes for documented access paths (doc 01 §7.7).

### 4. Review constraints and indexes
Check: NOT NULL where required, CHECK constraints match documented enums, uniqueness on references and idempotency keys, `balance >= 0`, FKs only within the same schema, indexes for each new query pattern, no redundant indexes.

### 5. Test
- Integration tests (`*IT`, Testcontainers `postgres:18-alpine`) run all migrations from an empty database on startup.
- Assert key constraints with tests (negative amount rejected, duplicate reference rejected, ledger UPDATE/DELETE rejected).
- JPA mappings match the schema (`spring.jpa.hibernate.ddl-auto=validate`).

## Verification
- `./mvnw verify` passes; `git diff` shows only new migration files under `db/migration`.

## Completion criteria
- New migration file(s) only, in the owning module's schema.
- Constraints and indexes reviewed and listed in the report.
- Migration tests passing, with actual command output summarised.
- Any destructive or locking step was approved by the user, or is deferred to a later contract step and documented.
