---
name: plan-feature
description: Produces a written implementation plan for a feature or significant change before any code is written — inspects documented requirements, identifies affected modules and components, dependencies, data changes, security implications, risks, acceptance criteria and test cases. Use at the start of any new feature, user story, PR, cross-module change or money-path change, or when asked to plan, scope or estimate work. Planning only; does not implement.
argument-hint: "<feature, story or FR id>"
---

# Plan a feature

Output is a plan, not code. Do not edit source, migrations, build files or documentation while running this skill. Implementation starts only after the user accepts the plan.

## Prerequisites
- Read `CLAUDE.md`.
- Know the feature request. If it is too vague to locate in the docs, ask one focused question before continuing.

## Workflow

### 1. Inspect requirements
- Find the feature in `documentations/05. ProductBusiness.md`: epics §3.1, functional requirements and acceptance criteria §3.2, business rules §4, journeys §8, roadmap and sprint §9, traceability §10.
- Record its scope label (`[MVP]`, `[MVP-Ext]`, `[Backlog]`) and sprint. If it is `[Backlog]` or outside the current scope, say so in the plan.
- Check `documentations/06. PortfolioMvpDecisions.md` (the MVP decision register): is the feature in MVP scope, and does an MVP-ADR change the documented requirement? Out-of-scope features need a new ADR and approval before planning continues.
- List anything the docs do not define, or where two docs disagree, as **Open questions**. Never fill gaps with invented financial, security or compliance values.

### 2. Identify affected components
- Owning module(s) (`CLAUDE.md` → Architecture; target-service mapping in doc 06 MVP-ADR-01).
- Public endpoints (doc 02 §2–§3, adjusted by doc 06). The MVP has no internal network APIs; cross-module calls go through the other module's `application` package.
- Layers touched in each module: web, application, domain, persistence (ArchUnit rules apply).
- Frontend screens and API client modules (doc 02 §7), if user-facing.
- Inspect the existing code for each component; if a module is still empty, record that as a dependency.

### 3. Dependencies
- Modules this change calls or is called by (in-process, through `application` services).
- Features or sprints that must land first (doc 05 §9, doc 04 §12).
- External services (MVP: SMTP via Mailpit only); configuration values and feature flags.
- New libraries or infrastructure: list only if unavoidable, and mark them as needing approval (doc 06 excludes Kafka, Redis, gateway, service discovery, mTLS).

### 4. Data and event changes
- Tables, columns, constraints and indexes per module schema (compare with doc 01 §7.2–§7.3, §7.7; one database, schema per module, doc 06 MVP-ADR-02). Mark each as additive or destructive; destructive changes need approval and an expand → migrate → contract sequence (`database-migration` skill).
- In-process events (e.g. after-commit notifications) and audit records the change must write.
- Ledger impact: posting template (doc 01 §6.4), atomic posting in the same transaction (doc 06 MVP-ADR-03), reconciliation coverage.
- Configuration values (limits, thresholds, TTLs) and where they come from — never hard-coded.

### 5. Security implications
Assess against `documentations/03. SecurityComplianceGovernance.md`:
- Authentication and token handling (§3.4); roles, permissions and ownership checks (§3.2–§3.3); maker-checker needs (§3.5).
- Endpoint exposure (deny by default, actuator/API docs), CORS/CSRF and cookie settings; implemented vs future controls in doc 06 §4.
- Relevant STRIDE threats (§4.2, cite IDs) and fraud controls (§4.3).
- Data classification and masking (§5); what must stay out of logs and events; audit events (§7).
- Decide whether `security-reviewer` review is required (rules in `CLAUDE.md`). For money movement, note that `secure-wallet-transfer` applies.

### 6. Risks
For each risk: description, likelihood/impact (Low/Medium/High), mitigation. Consider at least: money integrity (double spend, lost update, partial movement), transaction rollback, concurrency and locking, migration risk on hot tables, backward compatibility of the API, performance against doc 04 §1 targets, and scope creep beyond the documented requirement.

### 7. Acceptance criteria
- Start from the documented criteria in doc 05 §3.2; quote or paraphrase them with the source section.
- Add precise, testable statements for each documented error code and state transition (doc 02 §1.7, status machines in §2).
- Mark any criterion you derived rather than found as "Derived — confirm".

### 8. Test cases
Table of test cases mapped to acceptance criteria and layer (unit, web slice, integration with Testcontainers PostgreSQL, architecture/ArchUnit, security, frontend), following doc 04 §4.2. Include happy path, every documented failure, idempotency replay/reuse, concurrency, rollback, cross-customer access (identical 404), and external-service failure where relevant.

### 9. Implementation sequence
Ordered, small steps (migrations → domain → application → persistence → web/API → frontend → docs), each naming the agent and skill to use (`backend-engineer` + `implement-rest-api`, `database-migration`, `secure-wallet-transfer` for money paths, `frontend-engineer`, `test-engineer` + `testing-workflow`, `api-documentation`, then `code-reviewer` / `security-reviewer`).

## Plan template
```markdown
# Plan: <feature>
**Source:** <doc §> · **Scope:** <MVP | MVP-Ext | Backlog> · **Sprint:** <n>

## Summary
## Requirements (with doc references)
## Affected components
## Dependencies
## Data and event changes
## Security implications
## Risks
| Risk | L/I | Mitigation |
## Acceptance criteria
## Test cases
| # | Criterion | Test | Layer |
## Implementation sequence
## Open questions / decisions needed
```

## Verification
- Every requirement, rule and value in the plan cites a doc section or is marked "Derived — confirm" or listed as an open question.
- Each acceptance criterion has at least one test case; each documented error code in scope has a test.
- Nothing in the plan contradicts doc 06 without being flagged.

## Completion criteria
- The plan follows the template and is shown to the user.
- Open questions and approval-required items (destructive migrations, new dependencies, changes to documented financial/security values) are listed explicitly.
- No code or documentation was changed. Wait for the user to accept the plan before implementing.
