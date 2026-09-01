# Digital-Wallet-Platform

A secure, scalable, cloud-ready digital wallet platform: register, KYC, deposit, transfer, merchant/QR payments, bills (later slices), cards (later), and transaction history.

**Approved architecture** (do not treat other docs as a competing design):

- [Architecture document](docs/11.%20ArchitectureDesign/FInal/01.%20ArchitectureDocument.md) — 8 business microservices + API Gateway, Config Server, and Service Discovery
- [ERD](docs/11.%20ArchitectureDesign/FInal/02.%20ERD.md) — database-per-service
- [OpenAPI](docs/11.%20ArchitectureDesign/FInal/03.%20ApiSpecification.yaml) — `/api/v1` public, `/internal/v1` ledger and risk

Stack: Java 21, Spring Boot, PostgreSQL, Kafka, Redis, Docker, Kubernetes, AWS.  
Optional demo UI: React (Vite) in `web-app/` — [docs/17. FrontendUi.md](docs/17.%20FrontendUi.md). The platform is backend-first; the SPA only calls the API Gateway.

How docs relate: [docs/00. DocumentationIndex.md](docs/00.%20DocumentationIndex.md).  
Development sequence (checklist, no code yet): [docs/18. DevelopmentPlan.md](docs/18.%20DevelopmentPlan.md).  
Local run topology: [docs/15. LocalDevelopment.md](docs/15.%20LocalDevelopment.md).
