# ADR-001 Microservices in a Maven multi-module monorepo

- **Status:** Accepted
- **Date:** 2026-10-15

## Context
The engine has clearly separated responsibilities with different consistency and availability needs: the ledger needs strict
consistency, the CSM adapter needs high availability and message journalling, notifications must never block payments.
We want to practise realistic service boundaries while keeping the project easy to build and change in one place.

## Decision
- Each container from the [C4 container view](../c4/02-containers.puml) is a separate Spring Boot application (Java 25, Spring Boot 4.x).
- All services live in one Git repository as a **Maven multi-module** build: a root aggregator/parent POM with the Spring Boot BOM,
  shared plugin management and Java release level.
- Shared code is limited to small libraries: `iso20022-model`, `payment-domain`, `messaging`, `contracts`. No shared domain services.
- Each service owns its PostgreSQL database (schema); no service reads another service's tables.

## Consequences
- One command builds and tests everything; cross-service refactoring is atomic.
- Shared libraries are versioned with the repo, so contract changes are visible in one commit — but they must stay thin to avoid coupling.
- Local development needs docker-compose (PostgreSQL, Kafka) and several JVMs.
- Eight JVMs and seven databases are a lot for a small engine; the cost is accepted because practising service boundaries is a goal.
  To revisit: folding payment-api into payment-orchestrator would remove one hop on the critical path and one idempotency store
  (`Idempotency-Key` and `commandId` would become the same thing).
