# Claude Code workflow: agents and skills

How work moves through the project agents (`.claude/agents/`) and skills (`.claude/skills/`). Rules behind the gates are in `CLAUDE.md` → "Agents and Skills".

## 1. Delivery workflow

```mermaid
flowchart TD
    REQ(["Feature, story, PR or bug"]) --> PLAN["<b>plan-feature</b> skill<br/>requirements · components · dependencies<br/>data/events · security · risks<br/>acceptance criteria · test cases"]
    PLAN --> G1{"User accepts plan?<br/>open questions resolved?"}
    G1 -- "revise" --> PLAN
    G1 -- "yes" --> ROUTE{"What changes?"}

    subgraph IMPL["Implementation (write access)"]
        direction TB
        BE["<b>backend-engineer</b><br/>Spring Boot module code"]
        FE["<b>frontend-engineer</b><br/>React / TS web app"]
        BE -.- S_API["implement-rest-api"]
        BE -.- S_DB["database-migration"]
        BE -.- S_MONEY["secure-wallet-transfer"]
    end

    ROUTE -- "endpoints, domain, persistence" --> BE
    ROUTE -- "schema" --> BE
    ROUTE -- "balance can change" --> BE
    ROUTE -- "UI" --> FE

    BE --> TEST
    FE --> TEST
    TEST["<b>test-engineer</b> + testing-workflow<br/>failure paths · idempotency · concurrency<br/>run verified commands · report real results"]
    TEST --> TR{"Tests pass?"}
    TR -- "product bug" --> ROUTE
    TR -- "yes" --> DOCS["<b>api-documentation</b> skill<br/>sync doc 02 / doc 06 / springdoc · mismatch table"]

    DOCS --> CR["<b>code-reviewer</b> (read-only)<br/>correctness · architecture · contract · tests"]
    DOCS --> SQ{"Touches auth/tokens, money,<br/>security config, error responses,<br/>wallet/ledger/transfer migrations?"}
    SQ -- "yes" --> SR["<b>security-reviewer</b> (read-only)<br/>+ secure-wallet-transfer checklist"]
    SQ -- "no" --> VERIFY
    DOCS --> DQ{"Adds or changes a schema,<br/>Flyway migration, constraint<br/>or trigger?"}
    DQ -- "yes" --> DR["<b>database-reviewer</b> (read-only)<br/>schema per module · money types · constraints<br/>ledger triggers · locking · migration safety"]
    DQ -- "no" --> VERIFY
    CR --> VERIFY
    SR --> VERIFY
    DR --> VERIFY

    VERIFY["Main session verifies each finding<br/>against the code: confirmed / dismissed"]
    VERIFY --> G2{"Confirmed findings?"}
    G2 -- "yes" --> ROUTE
    G2 -- "no" --> DONE(["Ready to merge<br/>squash to main"])

    classDef skill fill:#eef6ff,stroke:#3b82f6,color:#1e3a8a;
    classDef ro fill:#f5f5f5,stroke:#6b7280,color:#111827;
    classDef gate fill:#fff7ed,stroke:#f97316,color:#7c2d12;
    class PLAN,DOCS,S_API,S_DB,S_MONEY skill;
    class CR,SR,DR ro;
    class G1,ROUTE,TR,SQ,DQ,G2 gate;
```

## 2. Responsibility map

Solid arrow = skill preloaded into the agent. Dashed arrow = invoked when the task needs it.

```mermaid
flowchart LR
    MAIN["Main session<br/>(orchestrator)"]

    subgraph WRITE["Agents with write access"]
        BE["backend-engineer"]
        FE["frontend-engineer"]
        TE["test-engineer<br/><i>test code only</i>"]
    end

    subgraph READ["Read-only reviewers"]
        CR["code-reviewer"]
        SR["security-reviewer"]
        DR["database-reviewer"]
    end

    subgraph SKILLS["Skills"]
        PF["plan-feature"]
        API["implement-rest-api"]
        DB["database-migration"]
        MONEY["secure-wallet-transfer"]
        TW["testing-workflow"]
        AD["api-documentation"]
    end

    MAIN -.-> PF
    MAIN -.-> AD
    MAIN --> BE & FE & TE & CR & SR & DR

    BE --> API
    BE --> DB
    BE --> TW
    BE -.-> MONEY
    BE -.-> AD
    FE --> TW
    TE --> TW
    SR --> MONEY
    DR --> DB

    API -.->|"schema step"| DB
    API -.->|"test step"| TW
    API -.->|"doc step"| AD
    MONEY -.->|"failure tests"| TW
```

## 3. Who owns what

| Agent | Can edit | Owns | Hands off to |
| --- | --- | --- | --- |
| `backend-engineer` | backend module code, migrations, tests | endpoints, DTOs, validation, authorisation, persistence, atomic money paths | `test-engineer`, reviewers |
| `frontend-engineer` | web app code and tests | screens, typed API client, UI states, accessibility | `test-engineer`, `code-reviewer` |
| `test-engineer` | test code only | test plan, coverage of rules and failure paths, real results | implementer (product bugs) |
| `code-reviewer` | nothing | correctness, module boundaries, contract, test gaps | `security-reviewer` for security issues |
| `security-reviewer` | nothing | auth, ownership, money integrity, web surface, data exposure | main session (verify findings) |
| `database-reviewer` | nothing | schema per module, keys, money types, constraints, indexes, ledger and audit triggers, locking support, idempotency tables, migration safety | main session (verify findings), `backend-engineer` for fixes |

| Skill | Used when | Produces |
| --- | --- | --- |
| `plan-feature` | before any feature, PR, cross-module or money-path change | plan + open questions; no code |
| `implement-rest-api` | an endpoint is added, changed or removed | endpoint + tests + doc update |
| `database-migration` | schemas, tables, constraints, indexes, triggers change | new Flyway migration + migration tests |
| `secure-wallet-transfer` | a balance can change (build or review) | rules ↔ tests + unresolved risks |
| `testing-workflow` | any code change or results to report | commands run + actual results |
| `api-documentation` | contract or event change, or a docs audit | updated docs + mismatch table |

## 4. Approval gates

| Gate | Who decides | Trigger |
| --- | --- | --- |
| Plan acceptance | user | every planned feature |
| Docs silent or conflicting, or a doc 06 deviation needed | user | any undefined financial, security or compliance value |
| Destructive or long-locking migration | user | drop/rename/narrow, data deletion, ledger trigger/grants, hot-table locks |
| New or upgraded dependency or infrastructure | user | any (doc 06 excludes Kafka, Redis, gateway, service discovery, mTLS) |
| Security review | required | auth/tokens/OTP, RBAC/ownership, money, security config, error responses, wallet/ledger/transfer migrations |
| Database review | required | any new or changed Flyway migration, schema, constraint, index or trigger; data-requirement extraction (documentations/07) |
| Findings | main session | each finding re-checked against code before fixing |
| Commit, push, merge, deploy | user | always explicit approval |
