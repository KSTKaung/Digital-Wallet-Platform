# Digital Wallet Platform — Technical Planning
## Detailed Project Structure
### Repository Structure
```
digital-wallet-platform/
├── wallet-service/                 # Main Spring Boot application (modular monolith)
│   ├── src/main/java/com/platform/wallet/
│   │   ├── module/
│   │   │   ├── wallet/              # Wallet module (own domain/application/infra/presentation)
│   │   │   ├── ledger/              # Ledger module (double-entry accounting)
│   │   │   ├── user/                # User & auth module
│   │   │   └── notification/        # Async notification consumer module
│   │   ├── config/                  # Cross-cutting app config
│   │   ├── security/                # Shared security infrastructure
│   │   └── common/                  # Shared kernel (used by all modules)
│   ├── src/main/resources/
│   ├── src/test/java/...
│   ├── Dockerfile
│   └── pom.xml
├── docker/
│   └── docker-compose.yml           # Postgres, Redis, Kafka, Zookeeper, Grafana, Prometheus
├── infra/                           # Terraform / k8s manifests (optional, later phase)
├── docs/
│   ├── architecture-decisions/      # ADRs
│   ├── er-diagram.png
│   └── openapi.yaml
└── README.md
```

### Detailed Structure — Single Module (e.g. wallet)
```
wallet/
├── domain/
│   ├── model/
│   │   ├── Wallet.java                    # Aggregate root — pure Java, no JPA annotations
│   │   ├── WalletId.java                  # Value object (wraps UUID)
│   │   ├── Money.java                     # Value object (amount + currency, immutable, arithmetic-safe)
│   │   └── WalletStatus.java              # Enum: ACTIVE, FROZEN, CLOSED
│   ├── repository/
│   │   └── WalletRepository.java          # PORT (interface) — domain defines what it needs
│   ├── service/
│   │   └── WalletDomainService.java       # Pure business rules (e.g. "can this wallet withdraw X?")
│   ├── exception/
│   │   ├── InsufficientBalanceException.java
│   │   └── WalletFrozenException.java
│   └── event/
│       ├── WalletCreatedEvent.java
│       └── WalletDebitedEvent.java
│
├── application/
│   ├── usecase/
│   │   ├── CreateWalletUseCase.java       # One class per business use case (Interactor pattern)
│   │   ├── DepositUseCase.java
│   │   ├── WithdrawUseCase.java
│   │   └── TransferFundsUseCase.java      # Orchestrates wallet + ledger together
│   ├── dto/
│   │   ├── CreateWalletCommand.java        # Input DTOs (commands)
│   │   └── WalletResult.java               # Output DTOs
│   ├── mapper/
│   │   └── WalletApplicationMapper.java    # domain <-> DTO
│   └── port/
│       ├── in/
│       │   └── WalletUseCase.java          # Interface the presentation layer calls
│       └── out/
│           ├── LedgerPort.java             # Interface application needs from ledger module
│           └── IdempotencyPort.java        # Interface for idempotency-key check (Redis)
│
├── infrastructure/
│   ├── persistence/
│   │   ├── entity/
│   │   │   └── WalletJpaEntity.java        # JPA-annotated entity (separate from domain model)
│   │   ├── repository/
│   │   │   └── WalletJpaRepository.java    # extends JpaRepository (Spring Data)
│   │   └── adapter/
│   │       └── WalletRepositoryAdapter.java # implements domain's WalletRepository port
│   ├── messaging/
│   │   ├── producer/
│   │   │   └── WalletEventPublisher.java   # publishes domain events to Kafka
│   │   └── outbox/
│   │       ├── OutboxEvent.java             # Outbox table entity
│   │       └── OutboxPublisherJob.java      # Scheduled job draining outbox -> Kafka
│   ├── cache/
│   │   └── RedisIdempotencyAdapter.java     # implements IdempotencyPort using Redis
│   ├── lock/
│   │   └── RedissonDistributedLockAdapter.java # wallet-level locking to prevent race conditions
│   └── client/
│       └── (external payment gateway clients, if any)
│
├── presentation/
│   ├── rest/
│   │   ├── WalletController.java           # @RestController, thin — delegates to use cases
│   │   └── AdminWalletController.java
│   ├── dto/
│   │   ├── request/
│   │   │   ├── CreateWalletRequest.java
│   │   │   └── TransferRequest.java
│   │   └── response/
│   │       ├── WalletResponse.java
│   │       └── TransactionResponse.java
│   ├── mapper/
│   │   └── WalletWebMapper.java             # request/response DTO <-> application DTO
│   └── validator/
│       └── TransferRequestValidator.java
│
├── config/
│   └── WalletModuleConfig.java              # Bean wiring specific to this module
│
└── tests/
    ├── unit/
    │   ├── domain/WalletDomainServiceTest.java
    │   └── application/TransferFundsUseCaseTest.java
    ├── integration/
    │   └── WalletControllerIntegrationTest.java   # Testcontainers: real Postgres/Redis
    └── contract/
        └── WalletApiContractTest.java
```

### Shared / Cross-Cutting Folders (application-level, not per-module)
```
config/
├── SecurityConfig.java          # Spring Security filter chain, password encoder bean
├── KafkaConfig.java             # Producer/consumer factories, topic definitions
├── RedisConfig.java             # RedisTemplate, connection factory
├── OpenApiConfig.java           # springdoc-openapi metadata
├── JacksonConfig.java           # ObjectMapper customization (e.g. BigDecimal serialization)
└── AsyncConfig.java             # Thread pools for async event handling

security/
├── jwt/
│   ├── JwtTokenProvider.java     # generate/validate access & refresh tokens
│   └── JwtProperties.java
├── filter/
│   └── JwtAuthenticationFilter.java
├── userdetails/
│   └── CustomUserDetailsService.java
└── ratelimit/
    └── Bucket4jRateLimitFilter.java

common/
├── exception/
│   ├── GlobalExceptionHandler.java   # @ControllerAdvice — maps domain exceptions to HTTP
│   └── ErrorCode.java                 # Centralized error code enum
├── response/
│   └── ApiResponse.java               # Standard envelope: { success, data, error, timestamp }
├── util/
│   ├── MoneyUtils.java                 # Safe BigDecimal arithmetic helpers
│   └── IdGenerator.java
├── constants/
│   └── AppConstants.java
└── annotation/
    └── IdempotencyKey.java             # Custom annotation to mark idempotent endpoints (AOP-driven)
```

### src/main/resources/
```
resources/
├── db/migration/
│   ├── V1__create_users_table.sql
│   ├── V2__create_wallets_table.sql
│   ├── V3__create_ledger_entries_table.sql   # immutable double-entry rows
│   └── V4__create_outbox_events_table.sql
├── application.yml
├── application-dev.yml
├── application-prod.yml
└── logback-spring.xml                         # structured JSON logging config
```

### Dependency Rule
```
presentation  →  application  →  domain
                       ↑
                infrastructure  (implements domain/application ports)
```
- **_domain_** depends on nothing else. No Spring, no JPA, no Kafka imports. Pure business logic and rules..
- **_application_** depends only on **_domain_**. It defines ports (interfaces) that **_infrastructure_** implements.
- **_infrastructure_** depends on **_domain_** + **_application_** (to implement their ports), plus external tech (Spring Data, Kafka, Redis clients).
- **_presentation_** depends only on **_application_** (never touches **_infrastructure_** or JPA entities directly).
- **_config_** and **_security_** wire everything together at the edges.

### Traced Example: A Transfer Request End-to-End
- **_presentation/rest/WalletController_** receives **_POST/wallets/transfer_** → validates request DTO
- Maps to **_application/dto/TransferCommand_**, calls **_application/usecase/TransferFundsUseCase_**
- Use case checks **_IdempotencyPort_** (→ **_infrastructure/cache/RedisIdempotencyAdapter_**) to reject duplicate retries
- Use case acquires a lock via **_LockPort_** (→ **_infrastructure/lock/RedissonDistributedLockAdapterr_**)
- Use case calls **_domain/service/WalletDomainService_** to validate business rules (sufficient balance, wallet not frozen)
- Use case calls **_LedgerPort_** to write paired debit/credit entries (ledger module)
- **_infrastructure/persistence/adapter/WalletRepositoryAdapter_** persists via Spring Data JPA
- Same DB transaction writes an **_OutboxEvent_** row (outbox pattern — atomic with the ledger write)
- **_infrastructure/messaging/outbox/OutboxPublisherJob_** picks it up and publishes to Kafka
- **_notification_** module consumes the event asynchronously and sends confirmation

### Naming Convention Cheat Sheet
| Layer	        | Suffix convention	| Example
| ------------------- | --------------------- | ---------------------- 
| Domain model	      | (none — plain noun)   | 	Wallet, Money
| Domain port	        | Repository / Port     | 	WalletRepository
| Use case            | UseCase	              | TransferFundsUseCase
| App port (outbound) | Port	                | LedgerPort
| Infra adapter       | Adapter               | 	WalletRepositoryAdapter
| JPA entity          | JpaEntity	            | WalletJpaEntity
| REST DTO            | Request / Response 	  | TransferRequest
| Domain event        | Event (past tense)	  | WalletDebitedEvent
