# Contributing

Conventions for the Banking Digital Wallet Platform. Design rules live in `documentations/` (docs 01–05 describe the target architecture; doc 06 records the approved MVP deviations); this file covers how we work in the repo.

## Prerequisites
| Tool | Version | Used for |
| --- | --- | --- |
| JDK | 21 | backend build and tests (Maven wrapper included; no Maven install needed) |
| Docker Desktop | Compose v2 | local stack, dev PKI, Testcontainers |
| Node.js | ≥ 22.12 (`.nvmrc`: 24) | frontend |
| Git | any recent | — |

## Repository layout
```
backend/          Spring Boot 4.1.2 modular application (com.portfolio.wallet.<module>)
frontend/         React + TypeScript + Vite + Tailwind web app, nginx config for Compose
compose.yaml      postgres, backend, frontend, optional mailpit
scripts/          dev-up.sh, dev-down.sh (Bash)
.github/          CI workflows, Dependabot, PR template
documentations/   01–05 target architecture; 06 MVP decisions (governs the MVP)
```

## Branches, commits and merging (doc 04 §4.1)
- Trunk-based on `main`. Branch prefixes: `feature/`, `fix/`, `chore/`, `refactor/`, `spike/`, `hotfix/`.
- [Conventional Commits](https://www.conventionalcommits.org/): `feat(wallet): add withdrawal limits`, `fix(gateway): …`, `chore: …`. Scope = service or `frontend`.
- PR required; checks must pass; branch up to date; **squash merge only** — the PR title becomes the commit message, so it must be a Conventional Commit.
- Hotfix: branch from the deployed tag, PR to `main`, patch tag.

## Backend conventions
- One application, organised by module: `com.portfolio.wallet.<module>.{web,application,domain,persistence}`. Other modules are used only through their `application` package. ArchUnit enforces this in `./mvnw test`.
- One PostgreSQL database, one schema per module; no cross-schema joins or foreign keys.
- Money is `BigDecimal` / `NUMERIC(19,2)`, at most 2 decimals (documentations/06 MVP-ADR-21); never floating point. Money movements are one database transaction (documentations/06 MVP-ADR-03, -08).
- Errors: `ApiException` + `ErrorCode` (`common.error`); `ApiException.notFound()` for missing and other users' resources.
- Flyway migrations in `src/main/resources/db/migration`; **never edit a merged migration**.
- Formatting: Spotless (google-java-format), checked in `./mvnw verify`.

## Frontend conventions
- Call the backend only through `src/api/client.ts`; requests go to `/api` on the same origin (Vite proxy / nginx). Never `/internal/**`.
- Style with Tailwind utility classes.
- Every async view handles loading, success and error states; errors use the documented `errorCode`.
- No `console` in app code (lint error); never log tokens, OTPs, card or KYC data.
- Prettier formats; ESLint lints; both run in CI.

## Commands

### Frontend (`cd frontend`)
| Task | Command |
| --- | --- |
| Install (clean, from lockfile) | `npm ci` |
| Dev server (http://localhost:5173) | `npm run dev` (copy `.env.example` to `.env.local` first) |
| Type check | `npm run typecheck` |
| Lint | `npm run lint` |
| Format / check | `npm run format` / `npm run format:check` |
| All tests | `npm test` |
| One test file | `npm test -- src/api/client.test.ts` |
| One test by name | `npm test -- -t "maps the documented error body"` |
| Coverage | `npm run coverage` |
| Production build | `npm run build` |

### Backend (`cd backend`; on Windows use `.\mvnw.cmd`)
| Task | Command |
| --- | --- |
| Everything (Spotless, unit, ArchUnit, Testcontainers ITs, JaCoCo); Docker required | `./mvnw verify` |
| Unit tests only (no Docker) | `./mvnw test` |
| One test method | `./mvnw test -Dtest=CorrelationIdFilterTest#preservesValidIncomingId` |
| One integration test | `./mvnw verify -Dit.test=WalletApplicationIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false` |
| Format | `./mvnw spotless:apply` |
| Dependency vulnerability scan (set the free `NVD_API_KEY`; without it the first NVD download takes hours and can fail with HTTP 429) | `./mvnw -Psecurity verify -DskipTests` |

### Local stack (repository root)
1. `cp .env.example .env` and set your own `DB_PASSWORD`.
2. `docker compose up -d --build --wait` (or `scripts/dev-up.sh`, add `--mail` for Mailpit).
3. Frontend http://localhost:5173 (nginx proxies `/api` to the backend), backend http://localhost:8080, OpenAPI JSON http://localhost:8080/v3/api-docs (local profile; no bundled Swagger UI, see documentations/06 MVP-ADR-15), Mailpit http://localhost:8025.
4. Stop with `scripts/dev-down.sh` (data kept) or `scripts/dev-down.sh --purge` (deletes the database volume after confirmation).

If a local PostgreSQL already listens on 5432, set `POSTGRES_HOST_PORT` (e.g. `55432`) in `.env`.

## Secrets and keys
- Never commit secrets, keys or certificates. `.env`, `*.key`, `*.p12` and `*.jks` are gitignored, and Gitleaks runs in CI.
- Copy `.env.example` to `.env` for local values. Dev keys and passwords are for local use only and are never used in staging or production (doc 03 §6.2).
