# Domain Entities (Admin)

## 1. Admin & Operations Domain
Admin & Operations  
├── Admin User Management  
├── Role Management  
├── Permission Management  
├── Customer Management  
├── Merchant Management  
├── KYC Review  
├── Transaction Monitoring  
├── Wallet Management  
├── Fraud Review  
├── Support Management  
├── System Configuration  
└── Audit Management  

## 2. Admin Roles
- SUPER_ADMIN
- ADMIN
- COMPLIANCE_OFFICER
- FINANCE_OFFICER
- RISK_OFFICER
- SUPPORT_AGENT
- AUDITOR
- OPERATIONS_MANAGER

**Example permissions:**
| Role                   | Main Responsibilities               |
| ---------------------- | ----------------------------------- |
| **SUPER_ADMIN**        | Full platform administration        |
| **ADMIN**              | Customer/merchant/system management |
| **COMPLIANCE_OFFICER** | KYC/AML review                      |
| **FINANCE_OFFICER**    | Settlement/reconciliation           |
| **RISK_OFFICER**       | Fraud/risk investigation            |
| **SUPPORT_AGENT**      | Customer support                    |
| **AUDITOR**            | Read-only audit access              |
| **OPERATIONS_MANAGER** | Operational monitoring              |

## 3.Admin Domain Entities
- AdminUser
- Role
- Permission
- UserRole
- RolePermission
- AdminSession
- AdminAction
- AdminApproval
- SystemConfiguration
- FeatureFlag

## 4. Admin Entity Relationships
```
┌──────────────┐
│  AdminUser   │
└──────┬───────┘
       │
       │ *
       ▼
┌──────────────┐
│   UserRole   │
└──────┬───────┘
       │
       │ *
       ▼
┌──────────────┐
│     Role     │
└──────┬───────┘
       │
       │ *
       ▼
┌──────────────┐
│RolePermission│
└──────┬───────┘
       │
       │ *
       ▼
┌──────────────┐
│  Permission  │
└──────────────┘
```

## 5. Admin Domain Services
### Customer Administration
```
CustomerAdminService
├── searchCustomer()
├── viewCustomer()
├── freezeCustomer()
├── unfreezeCustomer()
├── suspendCustomer()
└── reactivateCustomer()
```

### Merchant Administration
```
MerchantAdminService
├── searchMerchant()
├── reviewMerchant()
├── approveMerchant()
├── rejectMerchant()
├── suspendMerchant()
└── reactivateMerchant()
```

### KYC Administration
```
KycAdminService
├── getPendingApplications()
├── reviewApplication()
├── approveApplication()
├── rejectApplication()
└── requestAdditionalDocuments()
```

### Transaction Administration
```
TransactionAdminService
├── searchTransactions()
├── viewTransaction()
├── investigateTransaction()
├── flagTransaction()
└── requestReversal()
```

### Wallet Administration
```
WalletAdminService
├── viewWallet()
├── freezeWallet()
├── unfreezeWallet()
├── reviewWalletActivity()
└── updateWalletLimit()
```

## 6. Admin Business Rules
### Authorization Rules
```
BR-ADMIN-001
Only authenticated administrative users can access Admin APIs.

BR-ADMIN-002
Admin users must have the required permission for each operation.

BR-ADMIN-003
An admin cannot grant permissions that exceed their own authority.

BR-ADMIN-004
Auditors have read-only access.

BR-ADMIN-005
Sensitive operations require elevated authorization.
```

### Separation of Duties
```
BR-ADMIN-006

The admin who creates a sensitive financial adjustment
cannot approve the same adjustment.
```
**Example:**
```
Finance Officer A
      │
      ▼
Create Adjustment
      │
      ▼
Pending Approval
      │
      ▼
Finance Officer B
      │
      ▼
Approve
```

## 7. Admin Approval Workflow
```
Admin
 │
 ▼
Request Sensitive Action
 │
 ▼
PENDING_APPROVAL
 │
 ▼
Second Admin Reviews
 │
 ├── REJECTED
 │
 └── APPROVED
       │
       ▼
    Execute Action
       │
       ▼
    Audit Log
```

**Potential actions:**
- Change transaction limits
- Freeze/unfreeze account
- Merchant approval
- Refund approval
- Manual adjustment
- System configuration change

## 8. Admin Dashboard
```
┌─────────────────────────────────────────────┐
│             ADMIN DASHBOARD                 │
├─────────────────────────────────────────────┤
│                                             │
│ Customers       Merchants       Transactions│
│ 125,430         4,250          2,450,231    │
│                                             │
│ Pending KYC     Fraud Alerts   Failed Txns  │
│ 128             24             1,230        │
│                                             │
├─────────────────────────────────────────────┤
│ Recent Transactions                         │
│                                             │
│ Recent Security Events                      │
│                                             │
│ Pending Approvals                           │
└─────────────────────────────────────────────┘
```

**Backend APIs:**
```
GET /api/v1/admin/dashboard

GET /api/v1/admin/customers
GET /api/v1/admin/customers/{id}

POST /api/v1/admin/customers/{id}/freeze
POST /api/v1/admin/customers/{id}/unfreeze

GET /api/v1/admin/merchants
POST /api/v1/admin/merchants/{id}/approve
POST /api/v1/admin/merchants/{id}/reject

GET /api/v1/admin/kyc/applications
POST /api/v1/admin/kyc/{id}/approve
POST /api/v1/admin/kyc/{id}/reject

GET /api/v1/admin/transactions
GET /api/v1/admin/transactions/{id}

GET /api/v1/admin/fraud-alerts
GET /api/v1/admin/audit-logs

GET /api/v1/admin/approvals
POST /api/v1/admin/approvals/{id}/approve
POST /api/v1/admin/approvals/{id}/reject
```

## 9. Admin Security Architecture
### Level 1 — Authentication
```
Admin
 │
 ▼
Login
 │
 ▼
JWT
 │
 ▼
Spring Security
```

### Level 2 — Authorization
```
JWT
 │
 ▼
User Identity
 │
 ▼
Roles
 │
 ▼
Permissions
 │
 ▼
Resource Authorization
```
**Example:**
```
Admin has CUSTOMER_FREEZE
        +
Admin can access this customer
        +
Customer is in an allowable state
        +
Action requires no conflicting approval
```

### 10. Admin + Audit
**Architecture:**
```
Admin Service
      │
      ▼
Execute Action
      │
      ├──────────────► Domain Service
      │
      └──────────────► Audit Event
                              │
                              ▼
                           Kafka
                              │
                              ▼
                       Audit Service
```
**Example:**
```
Admin:
    admin-123

Action:
    FREEZE_CUSTOMER

Target:
    customer-456

Reason:
    Suspicious activity

Timestamp:
    2026-08-11T18:30:00

IP:
    x.x.x.x

Result:
    SUCCESS

Correlation ID:
    7f8c...
```
<!--
→ RBAC
→ Permission-based authorization
→ Resource-level authorization
→ Method-level security
→ JWT
→ Audit logging
→ Approval workflow
→ Transaction monitoring
→ Event-driven architecture
→ Idempotency
→ Distributed operations
→ Security controls
-->
