# Digital Wallet Platform — Technical Planning
## Technical Stack
### Core Backend
**Language/Framework:**
- Java 21
- Spring Boot 3.x

**Key Spring modules:**
- Spring Web (REST)
- Spring Data JPA
- Spring Security
- Spring Validation
- Spring AOP (for audit logging/transaction interceptors)

**Build tool:**
- Maven

**Architecture style:**
- Modular monolith to start (wallet, ledger, user, notification modules)

### Database Layer
**Primary DB:**
- PostgreSQL

**Schema design pattern:**
- Double-entry ledger (every transaction = a debit + credit entry, immutable rows, balance derived from sum of entries, not a mutable "balance" column)

**Migrations:**
- Flyway

**Connection pooling:**
- HikariCP

### Caching
- Redis(for balance read-caching, rate-limit counters, idempotency key storage, and distributed locks (e.g., Redisson) to prevent double-spend race conditions)

### Security & Auth
**Authentication:**
- Spring Security
- JWT

**Authorization:**
- Role-based (RBAC) — consider attribute-based for admin vs. user vs. merchant roles

**Encryption:**
- AES for sensitive data at rest
- TLS everywhere
- BCrypt/Argon2 for password hashing

**Rate limiting:**
- Redis-backed limiter

### Transaction Integrity & Async Processing
**Idempotency:**
- Idempotency-Key header pattern for all money-movement endpoints

**Event streaming:**
- Apache Kafka for transaction events

### API Layer
**RESTAPI:**
- OpenAPI for auto-generated, browsable documentation

**API Gateway:**
- Spring Cloud Gateway for centralized auth/rate-limiting

### Testing
**Unit:**
- JUnit 5
- Mockito

**Integration:**
- Testcontainers(spin up real Postgres/Redis/Kafka in tests — big credibility signal)

**Contract testing:**
- Spring Cloud Contract

**Load testing:**
- k6 or Gatling for transaction throughput scenarios

### DevOps & Infrastructure
**Containerization:**
- Docker
- Docker Compose

**Orchestration:**
- Kubernetes

**CI/CD:**
- GitHub Actions (build → test → containerize → deploy pipeline)

### Observability
**Logging:**
- SLF4J
- Logback
- Structured JSON logs

**Metrics:**
- Micrometer
- Prometheus

**Dashboards:**
- Grafana

**Tracing:**
- Spring Cloud Sleuth for distributed tracing across async flows

**Cloud (deployment target)**
- AWS (RDS for Postgres, ElastiCache for Redis, self-managed Kafka, ECS/EKS)





