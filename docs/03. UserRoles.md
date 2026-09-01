# Identify User Roles
## 1. Role Overview
| Role                   | Description                                            | Access Level          |
| ---------------------- | ------------------------------------------------------ | --------------------- |
| **Customer**           | Individual who owns and uses a wallet                  | Own data only         |
| **Merchant**           | Business that receives customer payments               | Own merchant data     |
| **Admin**              | Internal banking operations user                       | Administrative        |
| **Compliance Officer** | Handles KYC/AML and regulatory activities              | Compliance data       |
| **Support Agent**      | Handles customer support cases                         | Limited customer data |
| **System**             | Automated backend processes                            | Service-to-service    |
| **Super Admin**        | Manages system configuration and privileged operations | Full administrative   |

## 1.1 Customer
### Purpose
The Customer is the primary end user who uses the digital wallet to manage money and make payments.
### Responsibilities
- Register an account
- Complete KYC
- Manage profile
- Manage wallet
- View balance
- Deposit funds
- Withdraw funds
- Transfer money
- Pay merchants
- Scan QR codes
- Pay bills
- Manage beneficiaries
- View transaction history
- Download statements
- Manage notifications
- Manage security settings

### Permissions
```
CUSTOMER_PROFILE_READ
CUSTOMER_PROFILE_UPDATE

KYC_SUBMIT
KYC_VIEW

WALLET_VIEW
WALLET_DEPOSIT
WALLET_WITHDRAW

TRANSFER_CREATE
TRANSFER_VIEW
TRANSFER_CANCEL

PAYMENT_CREATE
PAYMENT_VIEW

QR_SCAN

BILL_PAYMENT_CREATE

BENEFICIARY_CREATE
BENEFICIARY_UPDATE
BENEFICIARY_DELETE

TRANSACTION_VIEW
STATEMENT_DOWNLOAD

NOTIFICATION_VIEW
NOTIFICATION_UPDATE

PASSWORD_CHANGE
MFA_MANAGE
DEVICE_MANAGE
```
### Access Control
Customer access must be restricted to their own resources.
**For example:**
```
GET /api/v1/wallets/{walletId}
```
**Conceptually:**
```
Customer
   |
   +-- Wallet A → ALLOWED
   |
   +-- Wallet B → DENIED
```
## 1.2 Merchant
### Purpose
A Merchant accepts payments from customers through the digital wallet.

### Responsibilities
- Manage merchant profile
- Generate payment QR
- Receive payments
- View transactions
- Process refunds
- Manage settlement information
- View sales reports
- Manage merchant users

### Permissions
```
MERCHANT_PROFILE_VIEW
MERCHANT_PROFILE_UPDATE

MERCHANT_QR_CREATE
MERCHANT_QR_VIEW
MERCHANT_QR_UPDATE

PAYMENT_VIEW
PAYMENT_REFUND_REQUEST

TRANSACTION_VIEW
TRANSACTION_EXPORT

SETTLEMENT_VIEW

REPORT_VIEW
```

### Access Control
A merchant can access only its own business data.
```
Merchant A
   |
   +-- Merchant A Transactions → ALLOWED
   |
   +-- Merchant B Transactions → DENIED
```

## 1.3 Admin
### Purpose
The Admin manages normal operational activities of the wallet platform.

### Responsibilities
- Manage customers
- Manage merchants
- Review account status
- Freeze/unfreeze accounts
- View transactions
- Manage transaction limits
- Review operational issues
- Generate reports

### Permissions
```
USER_VIEW
USER_SEARCH
USER_UPDATE
USER_FREEZE
USER_UNFREEZE

MERCHANT_VIEW
MERCHANT_UPDATE
MERCHANT_SUSPEND

TRANSACTION_VIEW
TRANSACTION_SEARCH

LIMIT_VIEW
LIMIT_UPDATE

REPORT_VIEW
REPORT_EXPORT

AUDIT_LOG_VIEW
```

### Access Control
Admin should not automatically have unlimited access.
**For example:**
```
Admin
 ├── View Customer        ✓
 ├── Freeze Customer      ✓
 ├── View Transaction     ✓
 ├── Delete Transaction   ✗
 ├── Change System Config ✗
 └── Manage Admins        ✗
```

## 1.4 Compliance Officer
### Purpose
Responsible for KYC, AML, and regulatory compliance.

### Responsibilities
- Review KYC applications
- Approve/reject KYC
- Investigate suspicious transactions
- Review customer risk
- Review AML alerts
- Generate compliance reports

### Permissions
```
KYC_VIEW
KYC_REVIEW
KYC_APPROVE
KYC_REJECT

AML_ALERT_VIEW
AML_ALERT_REVIEW
AML_CASE_CREATE
AML_CASE_UPDATE

RISK_PROFILE_VIEW
RISK_PROFILE_UPDATE

TRANSACTION_COMPLIANCE_VIEW

COMPLIANCE_REPORT_VIEW
COMPLIANCE_REPORT_EXPORT

AUDIT_LOG_VIEW
```

### Restrictions
```
TRANSFER_CREATE
PAYMENT_CREATE
WALLET_WITHDRAW
USER_DELETE
SYSTEM_CONFIG_UPDATE
```

## 1.5 Support Agent
### Purpose
Customer Support handles customer issues and operational requests.\

### Responsibilities
- Search customers
- View customer profile
- View transaction status
- Create support tickets
- Investigate failed transactions
- Escalate issues

### Permissions
```
CUSTOMER_SEARCH
CUSTOMER_PROFILE_VIEW

TRANSACTION_VIEW
TRANSACTION_STATUS_VIEW

SUPPORT_CASE_CREATE
SUPPORT_CASE_VIEW
SUPPORT_CASE_UPDATE
```

### Restrictions
```
Transfer Money       ✗
Withdraw Money       ✗
Approve KYC          ✗
Change Balance       ✗
Delete Transaction   ✗
```

## 1.6 Super Admin
### Purpose
The Super Admin is responsible for highly privileged platform administration.

### Responsibilities
- Manage administrative users
- Assign roles
- Manage permissions
- Configure system parameters
- Manage security policies
- Manage platform configuration

### Permissions
```
ADMIN_CREATE
ADMIN_VIEW
ADMIN_UPDATE
ADMIN_DISABLE

ROLE_CREATE
ROLE_VIEW
ROLE_UPDATE
ROLE_ASSIGN

PERMISSION_VIEW
PERMISSION_ASSIGN

SYSTEM_CONFIG_VIEW
SYSTEM_CONFIG_UPDATE

SECURITY_POLICY_VIEW
SECURITY_POLICY_UPDATE

AUDIT_LOG_VIEW
```

### Critical Security Rule
Super Admin should not be able to directly manipulate financial balances or transactions.
```
Change wallet balance     ✗
Delete transaction        ✗
Create fake transaction   ✗
```

## 1.7 System
#### Purpose
SYSTEM represents automated processes running inside the platform.

**Examples:**
- Scheduled jobs
- Transaction processors
- Notification services
- Fraud detection
- Settlement processing
- Reconciliation
- Interest calculation
- KYC processing
- Event consumers

### Responsibilities
- Process Transactions
- Process Payments
- Process Notifications
- Run Scheduled Jobs
- Process Kafka Events
- Run Reconciliation
- Generate Reports
- Detect Suspicious Transactions

### Permissions
System permissions should be service-specific.
```
Transaction Service
    TRANSACTION_PROCESS

Notification Service
    NOTIFICATION_SEND

Fraud Service
    FRAUD_ANALYSIS

Settlement Service
    SETTLEMENT_PROCESS

Reconciliation Service
    RECONCILIATION_PROCESS
```

## 2. Define Permissions
**Resource + Action**
### User
```
USER_CREATE
USER_READ
USER_UPDATE
USER_FREEZE
USER_UNFREEZE
```

### Wallet
```
WALLET_READ
WALLET_DEPOSIT
WALLET_WITHDRAW
WALLET_FREEZE
WALLET_UNFREEZE
```

### Transaction
```
TRANSACTION_CREATE
TRANSACTION_READ
TRANSACTION_SEARCH
TRANSACTION_EXPORT
TRANSACTION_REVERSE
```

### Payment
```
PAYMENT_CREATE
PAYMENT_READ
PAYMENT_REFUND
```

### KYC
```
KYC_SUBMIT
KYC_READ
KYC_REVIEW
KYC_APPROVE
KYC_REJECT
```

### Merchant
```
MERCHANT_CREATE
MERCHANT_READ
MERCHANT_UPDATE
MERCHANT_SUSPEND
```

### Administration
```
ROLE_CREATE
ROLE_UPDATE
ROLE_ASSIGN

PERMISSION_READ
PERMISSION_ASSIGN

SYSTEM_CONFIG_READ
SYSTEM_CONFIG_UPDATE
```

## 3. Define Responsibilities
| Activity             | Customer |  Merchant  |   Admin  |  Compliance |  Support  |  System |
| -------------------- | :------: | :--------: | :------: | :---------: | :-------: | :-----: |
| Register             |     ✅    |      ✅     |     ❌    |      ❌      |     ❌     |    ❌    |
| Manage Own Profile   |     ✅    |      ✅     |     ❌    |      ❌      |     ❌     |    ❌    |
| Submit KYC           |     ✅    |      ✅     |     ❌    |      ❌      |     ❌     |    ❌    |
| Approve KYC          |     ❌    |      ❌     |     ❌    |      ✅      |     ❌     |    ❌    |
| View Balance         |    Own   | Settlement |  Limited |   Limited   |     ❌     | Service |
| Transfer Money       |     ✅    |      ❌     |     ❌    |      ❌      |     ❌     | Process |
| Receive Payment      |     ❌    |      ✅     |     ❌    |      ❌      |     ❌     | Process |
| Refund               |     ❌    |   Request  | Approve* |   Review*   | Initiate* | Process |
| Freeze Account       |     ❌    |      ❌     |     ✅    | Can Request |     ❌     | Execute |
| Fraud Investigation  |     ❌    |      ❌     |  Limited |      ✅      |     ❌     |  Detect |
| View Audit Logs      |     ❌    |     Own    |     ✅    |      ✅      |  Limited  |  Write  |
| Manage Roles         |     ❌    |      ❌     |     ❌    |      ❌      |     ❌     |    ❌    |
| System Configuration |     ❌    |      ❌     |     ❌    |      ❌      |     ❌     | Service |

## 4. Define Access Control
Three layers of authorization.

### Layer 1 — Role-Based Access Control
Determine what type of user is making the request.
> User  
>  ↓  
> Role  
>  ↓  
> Permissions  

**Example:**
```
John
  ↓
CUSTOMER
  ↓
TRANSFER_CREATE
  ↓
POST /transfers
```

### Layer 2 — Permission-Based Access Control
User Permission.
**Example:**
```
@PreAuthorize("hasAuthority('TRANSACTION_READ')")
```

### Layer 3 — Resource-Level Authorization
Customer A should still not be able to access Customer B's wallet.

> Authentication  
>        ↓  
> Role Check  
>        ↓  
> Permission Check  
>        ↓  
> Resource Ownership Check  
>        ↓  
> Business Rule Check  
>        ↓  
> Allow / Deny  

**Example:**
```
GET /wallets/100

Customer A
    ↓
Has WALLET_READ?       YES
    ↓
Wallet 100 belongs to A? YES
    ↓
ALLOW
```
```
GET /wallets/200

Customer A
    ↓
Has WALLET_READ?       YES
    ↓
Wallet 200 belongs to A? NO
    ↓
DENY 403
```

## 5. Recommended RBAC Model
**Database Design:**
```
User
  |
  +---- UserRole
           |
           +---- Role
                   |
                   +---- RolePermission
                              |
                              +---- Permission
```
**For example:**
```
User
 └── John
       │
       └── CUSTOMER
              │
              ├── WALLET_READ
              ├── WALLET_DEPOSIT
              ├── WALLET_WITHDRAW
              ├── TRANSFER_CREATE
              ├── TRANSACTION_READ
              └── PAYMENT_CREATE
```

## 6. Access-Control Architecture
```
                Client
                  │
                  ▼
            API Gateway
                  │
                  ▼
          Authentication
            JWT / OAuth2
                  │
                  ▼
          Authorization
                  │
       ┌──────────┴──────────┐
       │                     │
   Role Check          Permission Check
       │                     │
       └──────────┬──────────┘
                  ▼
          Resource Ownership
                  │
                  ▼
          Business Rules
                  │
                  ▼
          Service Layer
                  │
                  ▼
              Database
```

### Example: Authentication Flow For Transfer Money
**Step 1 — Authentication**
Validate JWT Token.
```
Token valid?
    ↓
YES
```

**Step 2 — Role**
```
Role = CUSTOMER
```

**Step 3 — Permission**
Check
```
TRANSFER_CREATE
```

**Step 4 — Account Status**
```
Account ACTIVE?
KYC VERIFIED?
```

**Step 5 — Business Rules**
Check
```
Sufficient balance?
Daily limit exceeded?
Recipient valid?
Fraud risk acceptable?
```

**Step 6 — Execute**
```
Create Transaction
        ↓
Update Ledger
        ↓
Publish Transaction Event
        ↓
Send Notification
```

**Step 7 — Audit**
Record
```
Who
What
When
From Account
To Account
Amount
IP Address
Device
Transaction ID
```

## 7. Important Banking Security Principle
For a banking wallet, RBAC alone is not enough.
> RBAC + Permission-Based Access Control + Resource Ownership + Business Rules + Audit Logging

### Model
```
             Authentication
                   │
                   ▼
                  RBAC
                   │
                   ▼
              Permissions
                   │
                   ▼
          Resource Ownership
                   │
                   ▼
            Business Rules
                   │
                   ▼
             Authorization
                   │
             ┌─────┴─────┐
             │           │
           ALLOW        DENY
             │
             ▼
        Execute Action
             │
             ▼
          Audit Log
```
<!-- A realistic enterprise banking authorization design and provides excellent material that demonstrate Spring Security, JWT/OAuth2,  
RBAC, method-level security, resource authorization, audit logging, and secure microservice communication rather than implementing authentication  
as just a login feature.  -->
