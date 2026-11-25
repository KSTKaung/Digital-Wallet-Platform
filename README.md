# Digital Wallet Platform (portfolio MVP)

A digital wallet in Thai baht: customers register, pass a basic KYC review, then deposit, withdraw and send money to other wallets. Compliance staff review KYC applications and risky transfers, and an admin can freeze a wallet. Behind it sit a double-entry ledger, idempotent money requests, risk scoring and an audit trail.

> **No real money.** Deposits and withdrawals are simulated. There is no bank, card or payment provider behind them, and KYC is a basic review, not an identity check.

## What it shows
- **Money movement:** one database transaction per movement; wallets locked in a fixed order with an optimistic `version`; a balanced, append-only ledger; `Idempotency-Key` on every money request; a reconciliation job.
- **Risk:** a 0–100 score from configured rules; a transfer is allowed, monitored, held for manual review (up to 24 hours) or blocked.
- **Security:**
  - access token in memory only; refresh token in an `HttpOnly` cookie, rotated on every use;
  - Argon2id passwords, lockout, emailed one-time codes;
  - role and ownership checks, with an identical `404` for other people's data;
  - rate limits, append-only audit rows, and no secrets or personal data in logs.
- **Architecture:**
  - one Spring Boot application split into modules (`auth`, `user`, `wallet`, `transfer`, `ledger`, `transaction`, `risk`, `notification`, `audit`, `admin`). ArchUnit tests enforce the module boundaries, so modules can later become services;
  - one PostgreSQL database with one schema per module;
  - a React single-page app served by nginx on the same origin.

## Stack
| Part | Technology |
| --- | --- |
| Backend | Java 21, Spring Boot 4.1, JdbcClient (no JPA), Flyway, PostgreSQL 18 |
| Frontend | React 19, TypeScript, Vite, Tailwind CSS, React Router |
| Tests | JUnit 5, Mockito, Testcontainers, ArchUnit, JaCoCo; Vitest and Testing Library |
| Local stack | Docker Compose: PostgreSQL, backend, nginx with the built app, Mailpit for email |
| CI | GitHub Actions: build, tests and coverage gates, OpenAPI drift checks, a Compose smoke test, Gitleaks, OWASP Dependency-Check, an `npm audit` gate |

## Quick start
You need Docker Desktop (Compose v2) and Git Bash or another POSIX shell. JDK 21 and Node.js 22.12 or later are needed only to run the tests or the dev server outside Docker.

1. **Create `.env`** from the template:
   ```bash
   cp .env.example .env
   ```
2. **Set the database passwords** in `.env`: replace `change-me` for `POSTGRES_SUPERUSER_PASSWORD`, `WALLET_OWNER_PASSWORD` and `WALLET_APP_PASSWORD`.
3. **Generate the signing and hashing secrets** and paste the four lines over the placeholders in `.env`. The backend refuses to start without them.
   ```bash
   bash scripts/generate-dev-secrets.sh
   ```
4. **Optional: staff accounts.** To use the staff screens, uncomment `STAFF_ADMIN_EMAIL`, `STAFF_ADMIN_PASSWORD`, `STAFF_COMPLIANCE_EMAIL` and `STAFF_COMPLIANCE_PASSWORD` in `.env` and give them values (passwords of 12–128 characters). The backend creates the two accounts at startup.
5. **Start the stack:**
   ```bash
   docker compose up -d --build --wait      # or: scripts/dev-up.sh
   ```

| Service | Address |
| --- | --- |
| Web app | http://127.0.0.1:5173 |
| Mailpit (sign-up codes and other emails) | http://127.0.0.1:8025 |
| Backend API | http://127.0.0.1:8080/api/v1 |
| OpenAPI document (local profile only) | http://127.0.0.1:8080/v3/api-docs, and committed at [`openapi/openapi.json`](openapi/openapi.json) |

Use `127.0.0.1` if `localhost` reaches another server on your machine.

Stop the stack with `scripts/dev-down.sh`. Your data is kept; `--purge` also deletes the database, after a confirmation.

> **Compose is a local demo setup, not a deployment recipe.** It runs the `local` profile with the API document switched on, uses plain HTTP, binds every port to `127.0.0.1`, and creates the staff accounts from `.env`. A deployment would need HTTPS, a secret manager and the other controls listed as targets in [doc 06 §4](documentations/06.%20PortfolioMvpDecisions.md).

## Demo walk-through
1. **Register:** use any email address and a phone number in international format, for example `+66812345678`. Copy the 6-digit code from Mailpit, verify the account, then sign in.
2. **KYC:** in Profile, set your date of birth and country. Under KYC, create an application with a made-up document reference, then submit it.
3. **Approve the KYC:** sign in as the compliance account in a private window, open KYC reviews and approve the application. This creates the wallet.
4. **Move money:** back as the customer, deposit and withdraw. Each confirmation sends one request with one `Idempotency-Key`.
5. **Transfer:** register a second customer the same way. Send it money by its 11-digit wallet number; the confirm step shows the recipient's masked name.
6. **See a held transfer:** send more than 40,000 THB to a new recipient within a few minutes of other transfers. The transfer shows "Held for review". As compliance, open Transfer reviews to see the score, the rules and both parties, then approve or reject it.
7. **Freeze a wallet:** sign in as the admin account, open Wallet freeze, and freeze a wallet by customer id with a reason. Customer ids appear on the compliance KYC detail page. The customer sees the wallet as frozen until you unfreeze it.

## Tests and checks
| What | Command |
| --- | --- |
| Backend: formatting, unit tests, ArchUnit, Testcontainers integration tests, coverage gate (Docker running) | `cd backend && ./mvnw verify` (Windows: `.\mvnw.cmd verify`) |
| Backend unit tests only (no Docker) | `cd backend && ./mvnw test` |
| Frontend | `cd frontend && npm ci && npm run lint && npm run typecheck && npm run coverage && npm run build` |
| Frontend dev server with hot reload (the backend running on 8080) | `cd frontend && npm run dev` |
| Smoke test against a running stack (needs the staff accounts in `.env`) | `node scripts/smoke/smoke.mjs .env` |
| Regenerate the OpenAPI document after an API change, then the frontend types | in `backend/`: `./mvnw verify -Dit.test=OpenApiContractIT -Dtest=NONE -Dsurefire.failIfNoSpecifiedTests=false -Dopenapi.write=true`; then in `frontend/`: `npm run api:types` |

The integration tests are the evidence for the money rules: authorisation, insufficient funds, duplicate requests, rollback, concurrent transfers and the balance invariants. The smoke test only proves the stack starts and the main flow works. More commands and the conventions are in [CONTRIBUTING.md](CONTRIBUTING.md).

## Documentation
| Document | What it covers |
| --- | --- |
| [06 Portfolio MVP decisions](documentations/06.%20PortfolioMvpDecisions.md) | **What the MVP actually does.** The decision register, scope, and the security controls in place vs future. It governs where it differs from docs 01–05 |
| [01 High-level architecture](documentations/01.%20HighLevelArchitecture.md) | the long-term target: domain model, money flows, ledger design |
| [02 API documentation](documentations/02.%20ApiDocumentation.md) | API conventions, error model, endpoint contract, client guide (§7) |
| [03 Security, compliance and governance](documentations/03.%20SecurityComplianceGovernance.md) | roles, authentication, threat model, data protection |
| [04 Operations and DevOps](documentations/04.%20OperationsDevOps.md) | quality gates, CI, target operations |
| [05 Product and business](documentations/05.%20ProductBusiness.md) | requirements, business rules, user guides |
| [07 Database requirements](documentations/07.%20DatabaseRequirements.md) | data and financial-domain requirements |
| [08 Delivery plan](documentations/08.%20DeliveryPlan.md) and [08a review record](documentations/08a.%20PrReviewRecord.md) | how the MVP was built, PR by PR, with every review finding and decision |

## Known limits
The full list is the "Not implemented" rows of [doc 06 §4](documentations/06.%20PortfolioMvpDecisions.md). In short:
- simulated money only;
- no HTTPS, MFA or field encryption;
- the rate limiter is in memory, for a single instance (only the wallet-lookup and code limits are in the database);
- no customer search for staff, and no investigation case handling;
- no SAST, image scanning or SBOM in CI.

The risk rules and thresholds are published in this repository; a real deployment would keep them private.
