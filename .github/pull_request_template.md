## Scope
<!-- What this PR does and does not do. Link the plan / doc 06 ADRs it implements. -->

## Changes
-

## Tests
<!-- Commands actually run and their results. Do not tick boxes for checks that were not run. -->
- [ ] `./mvnw verify` (backend)
- [ ] `npm run lint && npm run typecheck && npm test && npm run build` (frontend)
- [ ] `docker compose config` / image builds (if containers changed)

## Security and money integrity
- [ ] No secrets, tokens, OTPs or personal data in code, logs, responses or fixtures
- [ ] Authorization and ownership checked; missing and foreign resources return identical 404
- [ ] Money changes: single transaction, ordered locks, idempotency, ledger balanced, rollback and concurrency tested
- [ ] `security-reviewer` run (required for auth, money, security config, error responses, wallet/ledger migrations)

## Documentation
- [ ] `documentations/06. PortfolioMvpDecisions.md` updated for any new deviation
- [ ] API docs updated (`api-documentation` skill) if the contract changed

## Known limitations
<!-- What is deliberately left out, open questions, follow-ups. -->
