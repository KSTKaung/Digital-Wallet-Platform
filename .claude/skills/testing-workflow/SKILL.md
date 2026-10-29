---
name: testing-workflow
description: Workflow for finding the relevant tests for a change, adding or updating them, running them with the project's verified commands (Maven Wrapper with JUnit 5, Mockito, Spring Boot Test, Testcontainers and ArchUnit for the backend; npm scripts with Vitest for the frontend), analysing failures and reporting actual results. Use whenever code is changed, a bug needs a reproducing test, or test results must be reported.
argument-hint: "<module or file under test>"
---

# Testing workflow

## Prerequisites
- Know what changed (`git diff`, or the files named in the task).
- Know the required test categories: `documentations/04. OperationsDevOps.md` §4.2 and the per-decision tests in `documentations/06. PortfolioMvpDecisions.md` §2.
- Backend integration tests need a running Docker engine (`docker info` succeeds). If it doesn't, report that; never mark those tests as passed.

## Commands (from `CLAUDE.md`; on Windows use `.\mvnw.cmd`)
| Scope | Command |
| --- | --- |
| Backend unit tests (`*Test`, no Docker) | `cd backend && ./mvnw test` |
| One class / method | `./mvnw test -Dtest=ClassName` · `-Dtest=ClassName#method` |
| One integration test (`*IT`) | `./mvnw verify -Dit.test=ClassNameIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false` |
| Everything (Spotless, unit, ArchUnit, ITs, JaCoCo) | `./mvnw verify` |
| Frontend | `cd frontend && npm test` · one file `npm test -- path` · one test `npm test -- -t "name"` · `npm run lint` · `npm run typecheck` |

If a command differs from what the build files or CI workflows (`.github/workflows/`) actually run, trust the build files and report the discrepancy.

## Workflow

### 1. Identify relevant tests
- Locate tests for the changed classes (same package under `backend/src/test/java`, or `*.test.ts(x)` next to frontend modules).
- Map each changed behaviour to the business rule it implements (`documentations/05. ProductBusiness.md` §4) and the error code it produces (`documentations/02. ApiDocumentation.md` §1.7, doc 06 MVP-ADR-19).

### 2. Implement or update tests
- **Unit** (JUnit 5 + Mockito): domain rules and services — amounts, limits, tiers, status transitions.
- **Web slice** (`@WebMvcTest` + Spring Security test): validation, 401/403/404, CORS, error body.
- **Integration** (`*IT`, `@SpringBootTest` + Testcontainers PostgreSQL with `@ServiceConnection`): migrations, transactions, idempotency, rollback, concurrency, KYC approval → wallet creation.
- **Architecture** (ArchUnit): `ArchitectureTest` must stay green; add a fixture plus a self-test when adding a rule.
- **Frontend** (Vitest + React Testing Library): loading/success/error states and documented error codes.
- Cover failure paths, assert on error codes and states, keep tests deterministic (fixed clocks, no real network, containers instead of shared services).

### 3. Run
- Run the narrowest relevant scope first, then the full `./mvnw verify` (and the frontend suite if touched).
- Capture the actual command and output summary.

### 4. Analyse failures
For each failure decide: test bug, product bug, or environment issue (e.g. Docker not running). Fix test bugs; for product bugs, report with evidence unless the task is to fix them. Never skip, `@Disabled`, or weaken assertions or ArchUnit rules to get green.

## Verification
- New tests fail without the change (or, for a bug, fail before the fix) and pass with it, where practical.
- The full suite still passes.

## Completion criteria
Report exactly:
1. Commands run.
2. Results: passed / failed / skipped counts for unit and integration tests separately.
3. Failures with test name, cause and classification.
4. Coverage of the changed behaviour and any remaining gaps.
If tests could not be run, say so and why. Never report tests as passing without having run them.
