# Tiny Payment Engine — Architecture

System design of a small payment engine for **SEPA Instant Credit Transfer (SCT Inst)** based on **ISO 20022** messaging.

## Scope

The engine models the payment back-end of a single bank (PSP) that participates in the SCT Inst scheme:

- as **Originator PSP** — it accepts instant credit transfers from its customers and sends them to the CSM;
- as **Beneficiary PSP** — it receives instant credit transfers from the CSM and credits its customers.

### Assumptions

| # | Assumption | Notes |
|---|---|---|
| A1 | Our bank plays both Originator and Beneficiary PSP roles. | Both flows share the same ledger. |
| A2 | The CSM (TIPS / RT1) and all counterparty PSPs are simulated by `csm-simulator`. | No real network connectivity. |
| A3 | Currency is EUR only. | Amounts are stored as minor units (`long`) or `BigDecimal`, never floating point. |
| A4 | Customer channels (mobile / online banking) are external API clients. | No UI in scope. |
| A5 | Scheme timings follow the SCT Inst rulebook: 10 s target execution time, 20 s time-out deadline for the Originator PSP. | Drives saga deadlines. |
| A6 | Regulation (EU) 2024/886 (Instant Payments Regulation) applies: Verification of Payee before initiation, daily sanctions screening of customers instead of per-transaction screening. | |

### Technology baseline

Java 25, Spring Boot 4.x, Maven multi-module monorepo, PostgreSQL (database per service), Kafka (KRaft),
REST for synchronous calls, Kafka for asynchronous messaging with transactional outbox.
See [decisions](decisions/).

## Contents

| Section | Description |
|---|---|
| [glossary.md](glossary.md) | Domain terms and ISO 20022 messages |
| [use-cases/](use-cases/README.md) | Actors, use case catalogue, use case diagram and specifications |
| [c4/01-system-context.puml](c4/01-system-context.puml) | C4 level 1 — system context |
| [c4/02-containers.puml](c4/02-containers.puml) | C4 level 2 — containers (services, databases, broker) |
| [c4/03-components-payment-orchestrator.puml](c4/03-components-payment-orchestrator.puml) | C4 level 3 — payment-orchestrator internals |
| [c4/03-components-payment-api.puml](c4/03-components-payment-api.puml) | C4 level 3 — payment-api internals |
| [c4/03-components-ledger-service.puml](c4/03-components-ledger-service.puml) | C4 level 3 — ledger-service internals |
| [c4/03-components-vop-service.puml](c4/03-components-vop-service.puml) | C4 level 3 — vop-service internals |
| [c4/03-components-risk-service.puml](c4/03-components-risk-service.puml) | C4 level 3 — risk-service internals |
| [c4/03-components-csm-gateway.puml](c4/03-components-csm-gateway.puml) | C4 level 3 — csm-gateway internals |
| [c4/03-components-notification-service.puml](c4/03-components-notification-service.puml) | C4 level 3 — notification-service internals |
| [c4/04-dynamic-outgoing-payment.puml](c4/04-dynamic-outgoing-payment.puml) | Dynamic view — outgoing instant payment (UC-04) |
| [c4/04-dynamic-incoming-payment.puml](c4/04-dynamic-incoming-payment.puml) | Dynamic view — incoming instant payment (UC-06) |
| [decisions/](decisions/) | Architecture Decision Records |

## Containers at a glance

| Container | Responsibility |
|---|---|
| `payment-api` | Customer-facing REST API: accepts `pain.001`, idempotency, status queries. |
| `payment-orchestrator` | Payment state machine and sagas (outgoing / incoming), deadlines, compensations. |
| `ledger-service` | Accounts and double-entry bookkeeping: holds and pending credits (memos), settle, release, credit. |
| `vop-service` | Verification of Payee — outgoing requests and answering requests from other PSPs. |
| `risk-service` | Per-transaction fraud rules, daily sanctions screening of customers, provisional freezes. |
| `csm-gateway` | ISO 20022 XML adapter to the CSM: `pacs.008`, `pacs.002`, `pacs.028` (`pacs.004`, `camt.056` and `camt.029` later, with recall). Single owner of the send-by and answer deadlines at the CSM boundary. |
| `notification-service` | Customer notifications: webhooks, `pain.002`, `camt.054`. |
| `csm-simulator` | Test-only: simulates TIPS / RT1 and counterparty PSPs. |

## Cross-cutting concerns not yet designed

These are known gaps. Each needs its own decision before the system can run 24/7 in anything close to production.

| Concern | What is missing |
|---|---|
| CSM liquidity | Our prefunded position at TIPS / RT1, its monitoring and the CSM's rejection when liquidity is insufficient. Today the CSM settlement account only mirrors postings; reconciliation is UC-12. |
| 24/7 operation | Zero-downtime deployments, backward-compatible DB migrations, PostgreSQL and Kafka high availability. SCT Inst has no maintenance window. |
| Security | Designed: authentication ([ADR-012](decisions/ADR-012-service-and-staff-authentication.md)), Kafka authentication and ACLs ([ADR-015](decisions/ADR-015-kafka-authentication-and-acls.md)), TLS inside the cluster ([ADR-016](decisions/ADR-016-tls-inside-the-cluster.md)), secrets and encryption at rest ([ADR-017](decisions/ADR-017-secrets-keys-and-encryption-at-rest.md)), rate limits ([ADR-018](decisions/ADR-018-rate-limiting.md)); requirements SEC-01 … SEC-09 in [specs/00 §12.3](../specs/00-engineering-baseline.md#123-production-requirements). Open: sign-off by security, platform, compliance and the channel teams ([Q-24, Q-30 … Q-36](../specs/40-open-questions.md#summary)); STRIDE threat model (SEC-09, M5). |
| Observability | Designed and accepted: SLOs, SLAs, alert rules and metrics in [specs/25](../specs/25-service-levels.md); telemetry stack in [ADR-013](decisions/ADR-013-telemetry-stack-otel-grafana.md), per-payment tracing against the 10 s budget in [ADR-014](decisions/ADR-014-payment-trace-model.md), pipeline, dashboards and alert delivery in [specs/26](../specs/26-observability.md). Self-hosted ([Q-26](../specs/40-open-questions.md#q-26-telemetry-hosting)). Open: data-protection sign-off on telemetry retention ([Q-27](../specs/40-open-questions.md#q-27-telemetry-retention-and-personal-data)), SLA sign-off ([Q-21](../specs/40-open-questions.md#q-21-slo-and-sla-figures)), paging tool names ([Q-28](../specs/40-open-questions.md#q-28-paging-and-ticketing-tools)). |

## Rendering diagrams

All diagrams are written in [PlantUML](https://plantuml.com) using the
[C4-PlantUML](https://github.com/plantuml-stdlib/C4-PlantUML) library from the PlantUML standard library
(`!include <C4/...>`), so no network access is needed.

- **IntelliJ IDEA:** install the *PlantUML Integration* plugin and open any `.puml` file.
- **CLI:**

  ```bash
  plantuml -tsvg -o out architecture/c4/*.puml architecture/use-cases/*.puml
  ```

- **Syntax check only:**

  ```bash
  plantuml -checkonly architecture/c4/*.puml architecture/use-cases/*.puml
  ```
