# 1. Identify Stakeholders
Stakeholders are individuals or groups who influence or are affected by the project.

## Primary Stakeholders
| Stakeholder        | Role                                  | Responsibilities                         | Priority |
| ------------------ | ------------------------------------- | ---------------------------------------- | -------- |
| Product Owner      | Defines product vision and priorities | Manage product backlog, approve features | High     |
| Business Analyst   | Collects and documents requirements   | Create BRD, user stories, workflows      | High     |
| Project Manager    | Oversees project delivery             | Planning, scheduling, risk management    | High     |
| Solution Architect | Designs system architecture           | Define architecture and technology stack | High     |
| Development Team   | Builds the application                | Backend, frontend, mobile development    | High     |
| QA Team            | Ensures software quality              | Testing, validation, automation          | High     |
| DevOps Engineer    | Manages deployment and infrastructure | CI/CD, Kubernetes, monitoring            | High     |

## Banking Stakeholders
| Stakeholder        | Responsibilities                          |
| ------------------ | ----------------------------------------- |
| Bank Administrator | Manage customers, merchants, transactions |
| Compliance Officer | AML, KYC, regulatory compliance           |
| Fraud Analyst      | Monitor suspicious transactions           |
| Finance Department | Settlement, reconciliation                |
| Customer Support   | Resolve customer issues                   |


## External Stakeholders
| Stakeholder                   | Responsibilities                                 |
| ----------------------------- | ------------------------------------------------ |
| Customers                     | Use digital wallet services                      |
| Merchants                     | Accept QR and wallet payments                    |
| Payment Gateway               | Process external payments                        |
| Banking Partners              | Fund transfers and settlements                   |
| Government Regulators         | Ensure compliance with financial regulations     |
| Third-party Service Providers | SMS, email, identity verification, notifications |

## Stakeholder Communication Matrix
| Stakeholder      | Frequency | Communication Method |
| ---------------- | --------- | -------------------- |
| Product Owner    | Daily     | Scrum Meetings       |
| Development Team | Daily     | Stand-up Meetings    |
| Business Analyst | Daily     | Workshops            |
| QA Team          | Daily     | Sprint Meetings      |
| Compliance Team  | Weekly    | Review Meetings      |
| Executives       | Monthly   | Progress Reports     |

# 2. Gather Functional Requirements
Functional requirements define what the system must do.

## Module 1: User Registration
### FR-001
The system shall allow users to register using:
- Mobile number
- Email address

### FR-002
The system shall verify OTP before account activation.

### FR-003
The system shall encrypt user passwords.

### FR-004
The system shall prevent duplicate accounts.

## Module 2: Authentication
### FR-005
The system shall allow secure login.

### FR-006
The system shall support JWT Authentication.

### FR-007
The system shall support Multi-Factor Authentication (MFA).

### FR-008
The system shall automatically expire inactive sessions.

## Module 3: Wallet
### FR-009
The system shall create a wallet after successful registration.

### FR-010
The system shall display wallet balance.

### FR-011
The system shall maintain transaction history.

### FR-012
The system shall allow wallet freeze/unfreeze.

## Module 4: Money Transfer
### FR-013
Users can transfer funds to another wallet.

### FR-014
Users can transfer funds to a bank account.

### FR-015
Users can schedule future transfers.

### FR-016
Users can cancel pending transfers.

## Module 5: QR Payment
### FR-017
Generate QR Code.

### FR-018
Scan QR Code.

### FR-019
Merchant payment.

### FR-020
Customer payment confirmation.

## Module 6: Bill Payment
### FR-021
Pay electricity bills.

### FR-022
Pay water bills.

### FR-023
Pay internet bills.

### FR-024
Mobile top-up.

## Module 7: Notifications
### FR-025
Send Email notification.

### FR-026
Send SMS notification.

### FR-027
Send Push notification.

### FR-028
Notify users after every successful transaction.

## Module 8: Admin Portal
### FR-029
Manage users.

### FR-030
Approve KYC.

### FR-031
Freeze user accounts.

### FR-032
View transaction reports.

### FR-033
Manage merchants.

### FR-034
Generate reports.

# 3. Gather Non-Functional Requirements
Non-functional requirements define how well the system performs.

## Performance
| Requirement           | Target     |
| --------------------- | ---------- |
| Login Response Time   | <2 seconds |
| Payment Response Time | <3 seconds |
| Balance Inquiry       | <1 second  |
| API Response Time     | <200 ms    |
| Dashboard Loading     | <3 seconds |

## Scalability
- Support 1 million users.
- Process 5,000 transactions per second.
- Support horizontal scaling.
- Allow independent scaling of microservices.

## Availability
- 99.99% uptime
- No single point of failure
- Automatic failover
- Multi-zone deployment

## Security
- Encrypt sensitive data using AES-256.
- Encrypt communication using TLS 1.3.
- Hash passwords with BCrypt.
- Support JWT authentication.
- Support OAuth2.
- Implement RBAC.
- Maintain audit logs.
- Protect against SQL Injection, XSS, and CSRF.
- Enforce rate limiting and API throttling.

## Reliability
- Automatic retries
- Circuit breaker pattern
- Idempotent transaction APIs
- Dead-letter queues for failed events
- Transaction rollback support

## Maintainability
- Clean Architecture
- SOLID principles
- Microservices
- API documentation (OpenAPI/Swagger)
- Centralized logging
- Unit and integration testing
- CI/CD automation

## Usability
- Responsive web interface
- Mobile-friendly design
- Accessible according to WCAG guidelines
- Multilingual support (future enhancement)

## Compliance
- PCI DSS (Payment Card Industry Data Security Standard)
- KYC (Know Your Customer)
- AML (Anti-Money Laundering)
- GDPR or applicable data privacy regulations
- Local financial regulations

# 4. Define Business Rules
Business rules govern system behavior and decision-making.

## Account Registration
### BR-001
Each mobile number must be unique.

### BR-002
Each email address must be unique.

### BR-003
Users must verify OTP before activation.

### BR-004
Users must complete KYC before accessing full wallet functionality.

## Wallet
### BR-005
Each customer can own only one primary wallet.

### BR-006
Wallet balance cannot be negative.

### BR-007
Wallet currency is fixed at account creation (multi-currency planned for a future release).

## Transactions
### BR-008
Transfers require sufficient wallet balance.

### BR-009
Transactions exceeding a configurable threshold require additional authentication.

### BR-010
Successful transactions cannot be modified.

### BR-011
Refunds are processed as new transactions linked to the original transaction.

### BR-012
Duplicate transaction requests using the same idempotency key must not create duplicate financial records.

## KYC
### BR-013
Identity verification is mandatory before increasing transaction limits.

### BR-014
Accounts failing KYC remain restricted.

## Security
### BR-015
Accounts are temporarily locked after five consecutive failed login attempts.

### BR-016
Sessions expire after 30 minutes of inactivity.

## Merchant
### BR-017
Merchants must complete verification before accepting payments.

### BR-018
Refund requests require merchant approval or follow the bank's dispute policy.

# 5. Identify Assumptions and Constraints
## Assumptions
| ID    | Assumption                                                      |
| ----- | --------------------------------------------------------------- |
| A-001 | Users have internet connectivity.                               |
| A-002 | Customers own a smartphone or web-enabled device.               |
| A-003 | SMS and email providers are available.                          |
| A-004 | Banking partner APIs are stable and accessible.                 |
| A-005 | Regulatory approval has been obtained before production launch. |
| A-006 | Users provide accurate KYC documents.                           |
| A-007 | Payment gateway services meet agreed SLAs.                      |

## Constraints
### Technical Constraints
- Backend must use Java 21 and Spring Boot 3.x.
- Architecture must follow a microservices approach.
- PostgreSQL is the primary transactional database.
- REST APIs are the default integration style, with asynchronous messaging via Kafka where appropriate.
- Containerization is required using Docker.
- Deployment targets Kubernetes.

### Security Constraints
- Sensitive data must be encrypted at rest and in transit.
- Authentication must support JWT and OAuth 2.0/OpenID Connect.
- All financial operations must be fully auditable.

### Business Constraints
- MVP must be delivered within six months.
- Initial release supports a single country and a single currency.
- Wallets require completed KYC for full functionality.
- Budget and staffing are fixed for the MVP phase.

### Regulatory Constraints
- Compliance with local financial regulations.
- Mandatory KYC and AML checks.
- Retention of transaction and audit records according to legal requirements.
- Regular security and compliance audits.
