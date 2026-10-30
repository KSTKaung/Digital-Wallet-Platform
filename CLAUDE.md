# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Portfolio MVP of a digital wallet: **one modular Spring Boot application** (`backend/`) plus a React SPA (`frontend/`), one PostgreSQL database, free/open-source tooling only. **No real money is processed**; deposits and withdrawals are simulated.

- `documentations/06. PortfolioMvpDecisions.md` is the **decision register for the MVP** (MVP-ADR-01…19, plus how the original D-01…D-16 apply). It governs wherever it conflicts with docs 01–05.
- Docs `01`–`05` describe the **long-term target** (8 microservices, Kafka, mTLS, …). Use them for business rules, API contract, security controls and test expectations; check doc 06 before applying their infrastructure decisions.
- When docs mention `FInal/01`, `FInal/03 ApiSpecification.yaml` or numbered docs `09`–`18`, those files are not in this repo.

| Doc | Use it for |
| --- | --- |
| `06. PortfolioMvpDecisions.md` | what the MVP does differently, scope in/out, implemented vs future security controls |
| `01. HighLevelArchitecture.md` | domain model, money flows, ledger design (§6), schema ideas (§7) |
| `02. ApiDocumentation.md` | API conventions (headers, idempotency, error model, rate limits), endpoint contract |
| `03. SecurityComplianceGovernance.md` | roles/permissions, authentication lifecycle, threat model, data protection |
| `04. OperationsDevOps.md` | quality gates, CI expectations, target operations model |
| `05. ProductBusiness.md` | requirements, acceptance criteria, business rules (§4) |
| `07. DatabaseRequirements.md` | consolidated database and financial-domain requirements (Draft), tagged documented / ADR / recommendation / open, with the unresolved decisions for PR 2–5 |
| `08. DeliveryPlan.md` | PR sequence and status, the planning and delivery procedure, and every accepted PR plan (update it whenever the owner accepts a plan or a PR is merged) |

## Commands

Backend (in `backend/`; on Windows use `.\mvnw.cmd`). Requires JDK 21; integration tests require a running Docker engine.

| Task | Command |
| --- | --- |
| Full check (Spotless, unit, ArchUnit, Testcontainers ITs, JaCoCo) | `./mvnw verify` |
| Unit tests only (no Docker) | `./mvnw test` |
| One unit test class / method | `./mvnw test -Dtest=CorrelationIdFilterTest` · `-Dtest=CorrelationIdFilterTest#preservesValidIncomingId` |
| One integration test | `./mvnw verify -Dit.test=WalletApplicationIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false` |
| Format | `./mvnw spotless:apply` (CI runs `spotless:check` inside `verify`) |
| Dependency vulnerability scan (CI `security.yml`; set `NVD_API_KEY`: without it the first NVD download takes hours and can fail with HTTP 429) | `./mvnw -Psecurity verify -DskipTests` |

Frontend (in `frontend/`, Node ≥ 22.12): `npm ci` · `npm run dev` · `npm run typecheck` · `npm run lint` · `npm run format:check` · `npm test` · one file `npm test -- src/api/client.test.ts` · one test `npm test -- -t "<name>"` · `npm run build`.

Full stack: copy `.env.example` to `.env`, then `docker compose up -d --build --wait` (or `scripts/dev-up.sh`; `scripts/dev-down.sh`, `--purge` deletes the DB volume after confirmation). Ports: frontend 5173 (nginx proxies `/api` to the backend), backend 8080, PostgreSQL 5432 (set `POSTGRES_HOST_PORT` if a local PostgreSQL already uses it), Mailpit 1025/8025 (`--profile mail`). All ports bind to 127.0.0.1.

## Architecture

- Root package `com.portfolio.wallet`. Modules: `auth`, `user` (profile + minimal KYC), `wallet`, `transfer`, `ledger`, `transaction` (read-only history), `notification`, `audit`, `admin`, `risk` (risk scoring and manual review, MVP-ADR-24), and shared `common`.
- Inside a module, only where needed: `web` (controllers, DTOs, DTO mappers) → `application` (services, `@Transactional` boundaries) → `domain` ← `persistence` (Spring Data repositories, JPA entities).
- **Enforced by ArchUnit** (`backend/src/test/java/.../architecture`): layer direction; other modules may use a module only through its `application` package; no cycles between modules; `common` depends on no module; domain has no web/servlet/application/persistence dependencies; controllers never return entities; no field injection; no `System.out`/`java.util.logging`. `ArchitectureRulesSelfTest` proves the rules catch violations. Don't weaken a rule to make code pass; propose an ADR in doc 06 instead.
- `common.error`: `ErrorCode` (doc 02 §1.7 codes plus MVP-ADR-19 additions) and `ApiException`. `common.web`: error body, `GlobalExceptionHandler`, correlation ID filter, `/internal/**` 404 filter. `common.security`: deny-by-default `SecurityConfig`, CORS, JSON 401/403.
- One database, **one schema per module**, one Flyway stream in `backend/src/main/resources/db/migration`; no cross-schema foreign keys or joins.

### Money and security rules (doc 06 MVP-ADR-03, -06, -08, -10, -13, -19)
- A money movement is **one database transaction**: take locks in one order (idempotency row → transfer row for review actions → both wallets `FOR UPDATE` in ascending id order, in one SQL statement → daily-limit usage rows), check balance and limits, update snapshots (with the optimistic `version` check), post the balanced ledger transaction, write the business record and audit row, commit. `ledgerStatus` is `POSTED` and is returned only after commit. Notifications run after commit.
- Ledger transactions and entries, deposits, withdrawals, risk assessments, rule hits and reviews, and audit rows are append-only (DB triggers reject UPDATE/DELETE/TRUNCATE, and the runtime role `wallet_app` has only SELECT/INSERT on them); wallets, daily limit usage, transfers, ledger accounts, investigations and sender risk state are never deleted (full list in MVP-ADR-02); Σ DEBIT = Σ CREDIT; amount > 0; unique `transaction_reference`; corrections by reversal.
- `Idempotency-Key` (UUID) is required on money POSTs and stored in PostgreSQL; same key + different body → `409 IDEMPOTENCY_KEY_REUSED`.
- Money is `BigDecimal` / `NUMERIC(19,2)`, at most 2 decimals (a non-zero third decimal is rejected, never rounded; trailing zeros are accepted), THB only (MVP-ADR-21). Limits come from configuration, never literals (values in MVP-ADR-18); daily limits use the Asia/Bangkok day (MVP-ADR-22). Undefined values are an open question, not something to invent.
- Wallet exists only after KYC approval: before it, `GET /api/v1/wallet` → `404 WALLET_NOT_FOUND`, money endpoints → `403 KYC_REQUIRED`.
- **No existence disclosure:** use `ApiException.notFound()` for both missing and other users' resources; 401 responses are identical for every path; validation messages name the field, never the value.
- Error body `{success:false, errorCode, message, correlationId, timestamp}`. A new error code needs a doc 06 entry.
- Never log or return passwords, tokens, OTPs, refresh-token values, KYC content or secrets. Secrets come only from environment variables.
- The rate limiter is in-memory and correct only for a single instance (MVP-ADR-06). The exceptions are the wallet-lookup limits and the OTP counters, which are stored in PostgreSQL (MVP-ADR-20, -23).

### Not in the MVP — do not add without a doc 06 ADR
Kafka/outbox, Redis, API gateway, Eureka/Config Server, mTLS/internal network APIs, Kubernetes/service mesh, payments/QR/merchants, cards, bill payment, real bank integration.

## Agents and Skills (`.claude/`)

Workflow diagrams and ownership tables: `.claude/WORKFLOW.md`.

| Work | Agent | Skills |
| --- | --- | --- |
| Backend module code, endpoints, persistence, migrations | `backend-engineer` | `implement-rest-api`, `database-migration`, `testing-workflow` (preloaded) |
| React/TS web app | `frontend-engineer` | `testing-workflow` (preloaded) |
| Missing coverage, bug reproduction, running suites | `test-engineer` | `testing-workflow` (preloaded) |
| Pre-merge correctness/architecture review | `code-reviewer` (read-only) | — |
| Security and money-integrity review | `security-reviewer` (read-only) | `secure-wallet-transfer` (preloaded) |
| Schema, migration and data-model review | `database-reviewer` (read-only) | `database-migration` (preloaded) |

Start each PR or feature with `plan-feature` and get the plan accepted before implementing. Use `secure-wallet-transfer` for any change that can alter a balance, and `api-documentation` after any endpoint, DTO, error-code or contract change.

- **Security review is required** for changes to authentication, JWT, refresh tokens, OTP, RBAC or ownership checks, money movement, wallet/ledger/transfer migrations, CORS/CSRF/cookie settings, actuator or API-docs exposure, error responses, and logging of sensitive fields.
- **Verify before acting:** test results count only if the commands were actually run and the output reported. Reviewer findings must cite `path:line` and a failure scenario; re-check each against the code before fixing, and say which were confirmed or dismissed.
- **Ask first** when the docs are silent or conflict, and before: changing documented financial/security values or doc 06 decisions, destructive or long-locking migrations, editing a merged migration, adding/upgrading dependencies or infrastructure, or dropping/resetting databases.

## Git
Trunk-based on `main`; branch prefixes `feature/`, `fix/`, `chore/`, `refactor/`, `spike/`, `hotfix/`; Conventional Commits (PR titles checked by commitlint); squash merge. **Never commit, push, merge or deploy without the owner's explicit approval.** Coverage target ≥ 80% on domain and application layers: the JaCoCo gate in `backend/pom.xml` enforces it on `*.domain` and `common.money` (application packages are added when they get code).
