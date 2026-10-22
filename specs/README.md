# Tiny Payment Engine — Developer Specifications

These specifications turn the [architecture](../architecture/README.md) into buildable work. The architecture says
*what* the system does and *why*; the specs say *exactly what to build*: modules, APIs, tables, messages, state
machines, configuration and acceptance criteria.

When a spec and the architecture disagree, the architecture wins and the spec is a bug — fix it. Where a spec had
to decide something the architecture leaves open, it says so and the decision is listed in
[40-open-questions.md](40-open-questions.md) for confirmation.

## Contents

| # | Spec | Read it when you… |
|---|---|---|
| 00 | [Engineering baseline](00-engineering-baseline.md) | start on any module: repo layout, stack, conventions, error format, IDs, money, time, local dev, CI |
| 01 | [Shared libraries](01-shared-libraries.md) | touch `payment-domain`, `iso20022-model`, `messaging` or `contracts` |
| 02 | [Messaging contracts](02-messaging-contracts.md) | produce or consume any Kafka message |
| 10 | [payment-api](10-payment-api.md) | UC-01, UC-02 (proxy), UC-07 |
| 11 | [payment-orchestrator](11-payment-orchestrator.md) | UC-01, UC-04, UC-05, UC-06 (sagas, state machine, timers) |
| 12 | [ledger-service](12-ledger-service.md) | UC-10, holds, pending credits, postings |
| 13 | [vop-service](13-vop-service.md) | UC-02, UC-03 |
| 14 | [risk-service](14-risk-service.md) | fraud rules (UC-04, UC-06), UC-09 |
| 15 | [csm-gateway](15-csm-gateway.md) | ISO 20022 to the CSM, send-by guard, answer deadline (ADR-004) |
| 16 | [notification-service](16-notification-service.md) | UC-08 |
| 17 | [csm-simulator](17-csm-simulator.md) | test infrastructure: CSM, counterparty PSPs, sanctions provider |
| 20 | [Testing strategy and scenario catalogue](20-testing-strategy.md) | write tests; the end-to-end scenarios every milestone must pass |
| 30 | [Delivery plan](30-delivery-plan.md) | plan work: milestones, epics, dependencies, parallel tracks |
| 40 | [Open questions and decisions](40-open-questions.md) | something is unclear; the decisions made where the architecture was silent, and the assumptions still open |

## How each service spec is structured

1. **Responsibilities** — and what the service explicitly does *not* do.
2. **Module layout** — packages mapped to the C4 component view.
3. **REST API** — endpoints, request / response bodies, error codes.
4. **Messaging** — topics produced and consumed, dedup keys.
5. **Data model** — tables (PostgreSQL DDL sketch), constraints, indexes.
6. **Behaviour** — state machines, algorithms, timers, concurrency rules.
7. **Configuration** — `tpe.*` properties with defaults.
8. **Non-functional requirements** — latency, throughput, retention.
9. **Acceptance criteria** — numbered (`<SVC>-AC-nn`), each traceable to a use case or ADR.

## Conventions used in the specs

- **MUST / MUST NOT / SHOULD / MAY** are used in the RFC 2119 sense.
- JSON examples show field names exactly as on the wire (camelCase). Omitted optional fields are `null` / absent.
- DDL is a sketch: column names, types and constraints are binding; exact index names and migration split are not.
- Use case references (`UC-04 5a`) point at the numbered steps in [architecture/use-cases](../architecture/use-cases/README.md).

## Definition of Done (every story)

- Code merged to `main` through a reviewed pull request; CI green (build, unit, integration, ArchUnit, contract tests).
- Acceptance criteria it implements have automated tests whose names carry the criterion id (e.g. `LED_AC_04_releaseOfUnknownHoldCreatesTombstone`).
- New or changed REST endpoints are in the service's OpenAPI document; new or changed messages are in `contracts` with a golden sample.
- Every new or changed call between our services (REST or Kafka) has a Pact interaction for each response the
  consumer handles, verified by the provider ([20 §10](20-testing-strategy.md#10-consumer-driven-contract-testing-pact)).
- Flyway migration is backward compatible with the previous release (see [00 §9](00-engineering-baseline.md#9-database-and-migrations)).
- Metrics, logs and trace attributes listed in the spec are emitted.
- Spec updated if the implementation had to deviate.
