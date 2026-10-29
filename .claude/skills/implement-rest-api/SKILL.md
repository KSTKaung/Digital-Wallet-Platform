---
name: implement-rest-api
description: Step-by-step workflow for adding or changing a REST endpoint (/api/v1/**) in the modular Spring Boot wallet application — requirements check, endpoint plan, controller/DTO/service/persistence implementation in the owning module, validation, authorisation, tests and API doc update. Use whenever a task creates, changes or removes an HTTP endpoint.
argument-hint: "<module> <METHOD /path or feature>"
---

# Implement a REST API endpoint

## Prerequisites
- `backend/` builds (`./mvnw test` passes before you start).
- You know which module owns the endpoint (`CLAUDE.md` → Architecture; target mapping in doc 06 MVP-ADR-01).

## Workflow

### 1. Inspect requirements
- Check `documentations/06. PortfolioMvpDecisions.md`: is the feature in MVP scope, and does an ADR change the documented behaviour?
- Find the endpoint in `documentations/02. ApiDocumentation.md` (§2 core, §3 extensions, §8 backlog). Note method, path, role, request, success status, error codes and idempotency.
- Find the governing business rules in `documentations/05. ProductBusiness.md` §3.2 and §4, and the authorisation rules in `documentations/03. SecurityComplianceGovernance.md` §3.
- If the endpoint is not documented, is out of MVP scope, or the task conflicts with the docs, stop and ask. Do not design an undocumented contract silently.

### 2. Plan the change
Write a short plan before coding:
- Module and package: `com.portfolio.wallet.<module>.{web,application,domain,persistence}`.
- Request/response DTOs and their validation rules.
- Application service method (transaction boundary) and domain rules involved.
- Calls into other modules: only through their `application` package.
- Persistence changes (if any → use the `database-migration` skill).
- Error codes per failure.
- Tests to add.

### 3. Implement (follow existing patterns in the module)
- **web**: `@RestController` + request/response records. Bean Validation on DTOs (`@NotNull`, `@Positive`, `@Digits(integer = 15, fraction = 4)` for money, …). Never return entities. Add springdoc annotations only where they add information.
- **application**: one service method per use case; `@Transactional` here, not in controllers.
- **domain**: business rules and invariants.
- **persistence**: Spring Data repositories and entities for the module's own schema only.
- Money POSTs: require `Idempotency-Key`; same key + same body replays the original response; same key + different body → `409 IDEMPOTENCY_KEY_REUSED` (doc 02 §1.4, doc 06 MVP-ADR-06). Money responses include `ledgerStatus`, built after commit.
- Correlation ID is handled by `CorrelationIdFilter`; don't re-implement it.

### 4. Enforce authorisation
- URL or method security with the documented role/permission.
- Ownership check against the authenticated subject for every resource id; missing **and** foreign ids → `ApiException.notFound()` (doc 06 MVP-ADR-19).
- Money operations also require user `ACTIVE`, approved KYC and wallet `ACTIVE` (doc 03 §3.3).

### 5. Errors
Throw `ApiException(ErrorCode, message)` from `common.error`; `GlobalExceptionHandler` produces the documented body. Messages never contain secrets, personal data or rejected values. A new error code needs a doc 06 entry and approval.

### 6. Test
Use the `testing-workflow` skill. Minimum:
- Web-layer test: validation error (400 `VALIDATION_ERROR`), unauthenticated (401), wrong role (403), foreign resource (identical 404).
- Service unit tests for each business rule and error code.
- Integration test (`*IT`, Testcontainers PostgreSQL) for persistence and transaction behaviour.
- Idempotency replay and reuse tests for money POSTs.
- `ArchitectureTest` still passes.

### 7. Update API documentation
Use the `api-documentation` skill to record the endpoint and any contract change.

## Verification
- `./mvnw verify` passes; report the actual output.
- Request/response fields, status codes and error codes match doc 02 or the doc 06 deviation that applies.

## Completion criteria
- Endpoint implemented in the owning module with validation, authorisation and documented error mapping.
- Tests added for success and every documented failure; all passing.
- API documentation updated or a documented gap reported.
- Changes to auth, money paths, security config or error responses are flagged for `security-reviewer`.
