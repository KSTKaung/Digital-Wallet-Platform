---
name: api-documentation
description: Workflow for keeping API documentation in sync with the implementation — inspect controller behaviour and the generated OpenAPI (springdoc), compare with documentations/02. ApiDocumentation.md and the MVP deviations in documentations/06, update the established docs, and report mismatches. Use after any endpoint, DTO, error code or contract change, or when asked to audit API docs against the code.
argument-hint: "<endpoint, module or 'audit'>"
---

# API documentation

## Prerequisites
Locate the established documentation:
- `documentations/02. ApiDocumentation.md`: the API guide for the target system (conventions §1, endpoints §2–§3, client guide §7, backlog §8).
- `documentations/06. PortfolioMvpDecisions.md`: approved MVP deviations from that contract (e.g. MVP-ADR-03 `ledgerStatus`, -11 transaction history, -14 refresh-token transport, -19 error codes).
- The **generated OpenAPI** from springdoc: run the backend with `SPRING_PROFILES_ACTIVE=local` and read `http://localhost:8080/v3/api-docs` (disabled outside the `local` profile). The canonical YAML named in doc 02 (`FInal/03 ApiSpecification.yaml`) is not in this repository.

## Workflow

### 1. Inspect actual behaviour
For each endpoint in scope, read the controller, DTOs, validation annotations, authorisation rules, exception mapping and response status, and the generated OpenAPI. Record: method, path, auth/role, request fields and constraints, success status and body, error statuses and codes, idempotency requirement, `ledgerStatus` presence.

### 2. Compare with the documented contract
Check each recorded item against doc 02, adjusted by doc 06. Also check:
- Naming conventions from doc 02 §8: `receiverCustomerId`, `/wallet`, `/payments/{id}/refunds`, `/admin/kyc/applications/{id}/approve`.
- Error body shape and codes (doc 02 §1.7, doc 06 MVP-ADR-19), pagination shape (§1.5), headers (§1.3).
- Scope: endpoints doc 06 places out of MVP scope must not appear in the implementation.

### 3. Decide what to update
- Code matches doc 02 or an approved doc 06 deviation, but a doc is stale → update the doc.
- Code contradicts both and no approved decision supports it → **do not** change the doc to match; report it as an implementation defect or a decision needed.
- New deviations from doc 02 need a doc 06 ADR and user approval.

### 4. Update
- Edit the matching rows in doc 02 or doc 06 in their existing style (same columns, labels, terse notes). Do not restructure the documents or rewrite doc 02's target-architecture content.
- Improve the generated OpenAPI with springdoc annotations (summaries, error responses) only where the code alone is ambiguous.
- Keep the version policy: additive changes stay in v1, breaking changes → `/api/v2` (doc 02 §1.9).

### 5. Validate
- The generated `/v3/api-docs` loads without errors, and documented endpoints appear with the right status codes.
- Markdown tables render (consistent column counts); section references still point to existing sections.

## Verification
Re-read every changed doc section against the code once more; for the scope reviewed, nothing documented is missing from the code and vice versa.

## Completion criteria
Report:
1. Docs updated (file and section).
2. **Mismatch table**: endpoint · aspect · code says · doc says · action (doc updated / code defect / decision needed).
3. Whether the generated OpenAPI was checked, and how.
