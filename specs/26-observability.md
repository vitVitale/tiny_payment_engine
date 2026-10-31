# 26 Observability: telemetry pipeline, tracing, dashboards and alert delivery

[25](25-service-levels.md) says *what* the engine measures and when it pages. This spec says *how*: what each
service emits, how telemetry travels and where it is stored, how a single payment is traced against its deadline,
how alerts are generated and delivered, and which dashboards exist. Decisions:
[ADR-013](../architecture/decisions/ADR-013-telemetry-stack-otel-grafana.md) (stack) and
[ADR-014](../architecture/decisions/ADR-014-payment-trace-model.md) (payment trace model).

## 1. Requirements

Functional:

| # | Requirement | Source |
|---|---|---|
| OR-1 | Every SLI, burn-rate rule and cause alert of [25 §5, §9](25-service-levels.md#5-component-slos) is evaluated continuously in production and in the performance environment, from the same rule files | 25 §9, 20 §5 |
| OR-2 | Given a `paymentId`, `txId` or `uetr`, an engineer sees the payment's whole automated path across services in one trace, with the time used against `sendBy` / the answer deadline at each hop | architecture README gap |
| OR-3 | Logs, traces and metrics link to each other: exemplar → trace, trace → logs, log line → trace | — |
| OR-4 | Pages reach on-call with a runbook and a dashboard link; a dead alert pipeline pages too | 25 §9, §10 |
| OR-5 | Personal data never reaches telemetry at INFO and above; if it does, we know within minutes | 00 §11, INV-14 |
| OR-6 | Dashboards, rules, routing and pipeline configuration live in the repository and are deployed from it | 00 §14 |

Non-functional:

- **Isolation.** Telemetry never blocks or fails a business operation. Exporters drop on overload; nothing on the
  payment path waits for the pipeline. A full telemetry outage changes no tier-0 SLI (verified by OBS-AC-11).
- **Freshness.** Metrics queryable ≤ 60 s after emission (scrape 15 s + remote write); logs and traces ≤ 30 s.
- **Retention.** Metrics 13 months, logs 30 days, traces 14 days ([Q-27](40-open-questions.md#q-27-telemetry-retention-and-personal-data)).
  Telemetry is not the system of record: payments, transitions, the gateway journal and audit tables are.
- **Scale.** Sized for 100 payments/s sustained and 300/s peak ([Q-13](40-open-questions.md#q-13-target-load)), §14.
- **Availability.** The alert path (rule evaluation → Alertmanager → paging) at least 99.9 %; the stack runs in a
  failure domain separate from the engine (SLO-PLT-05, -06 in [25 §5.9](25-service-levels.md#59-platform)).

Out of scope: business intelligence and reporting, audit trails (database tables), the security event pipeline
(SIEM), CSM liquidity monitoring (not designed yet).

## 2. Overview

```
 engine namespace (per node)                    observability namespace / node pool
┌───────────────────────────────┐
│ service pod                   │   OTLP/gRPC      ┌──────────────┐  OTLP   ┌───────────────┐   ┌───────┐
│  traces ──────────────────────┼─────────────────►│ Alloy agent  ├────────►│ Alloy gateway ├──►│ Tempo │
│  /actuator/prometheus ◄───────┼──── scrape 15 s ─┤ (DaemonSet)  │         │ (≥ 2 replicas)├──►│ Loki  │
│  stdout (ECS JSON) ──► node ──┼──── tail ───────►│ node exporter│         │ PII redaction │   └───────┘
└───────────────────────────────┘                  └──────┬───────┘         │ disk queue    │
 platform exporters: postgres, kafka, kube-state,         │ remote write    └───────────────┘
 consumer-lag-exporter, invariant-checker ───scrape──►    ▼
                                                    ┌──────────┐ ruler ┌──────────────┐  page   ┌─────────────┐
 k6 probes (outside the cluster) ── remote write ──►│  Mimir   ├──────►│ Alertmanager ├────────►│ paging tool │
                                                    └────┬─────┘       │ (Mimir, HA)  ├─ticket─►│ tracker/chat│
                                Loki ruler ─────────────────────────────►              ├─Watchdog► heartbeat │
                                                         │             └──────────────┘         └─────────────┘
                                                    ┌────▼─────────────────────────────┐
                                                    │ Grafana: dashboards, Explore,    │  OIDC (ADR-012 IdP)
                                                    │ exemplars ↔ traces ↔ logs        │
                                                    └──────────────────────────────────┘
```

| Signal | Produced by | Format | Transport | Store | Retention |
|---|---|---|---|---|---|
| Metrics | Micrometer, platform exporters, probes | Prometheus / OpenMetrics with exemplars | scrape by Alloy agent → remote write | Mimir | 13 months |
| Traces | Micrometer Tracing + OTel bridge | OTLP | OTLP/gRPC → agent → gateway | Tempo | 14 d |
| Logs | Spring Boot structured logging (ECS JSON), stdout | JSON lines | file tail → agent → gateway (OTLP) | Loki | 30 d |
| Profiles | Pyroscope Java agent (performance environment; production per ADR-013) | pprof | push to Pyroscope | Pyroscope | 7 d |
| Alerts | Mimir and Loki rulers | Prometheus rules | — | Alertmanager | — |

## 3. Resource identity and common labels

Every signal of a service instance carries the same identity, set once in `libs/observability` from environment
variables the deployment provides: the OpenTelemetry standard `OTEL_SERVICE_NAME` and `OTEL_RESOURCE_ATTRIBUTES`
(`service.namespace=tpe,service.version=…,service.instance.id=…,deployment.environment.name=…`). Locally, without
them, the service name is `spring.application.name`, the environment `local` and the version `unknown`.

| OTel resource attribute | Prometheus label | Loki label | Value |
|---|---|---|---|
| `service.name` | `application` (Micrometer common tag, already used by [25 §9.1](25-service-levels.md#91-burn-rate-alerts-every-slo-with-a-percentage)) | `service_name` | `payment-orchestrator`, … |
| `service.namespace` | `namespace` (target label) | `service_namespace` | `tpe` |
| `service.version` | `version` (common tag) | structured metadata | release version + commit SHA |
| `service.instance.id` | `instance`, `pod` (target labels) | `pod` | pod name |
| `deployment.environment.name` | `env` (external label) | `env` | `local`, `systemtest`, `perf`, `staging`, `prod` |

`cluster` is an external label added by Alloy. No other label is added by the pipeline.

## 4. Instrumentation (`libs/observability`)

New library module (Spring Boot auto-configuration, enabled by default). Dependencies: `payment-domain`, Micrometer,
Micrometer Tracing, OpenTelemetry API / SDK / OTLP exporter, Spring Boot autoconfigure; Spring Web, the servlet API
and Boot's OpenTelemetry tracing and RestClient modules as `provided` ([00 §1](00-engineering-baseline.md#1-repository-layout)).
It contains no business logic. `messaging` depends on it; services get it through `messaging` or directly, and add
`spring-boot-starter-opentelemetry` (tracing), `spring-boot-starter-actuator` and, for JDBC spans,
`datasource-micrometer-spring-boot`. Each part is active only when the library it builds on is present.

| Package | Contents |
|---|---|
| `config` | common tags, resource attributes, SLO histogram boundaries ([25 §11](25-service-levels.md#11-metrics-to-add)) as defaults, OTLP exporter settings, observation predicates (§4.2) |
| `payment` | `PaymentTelemetry`: sets MDC, span attributes and the budget attributes of §5.4 from typed values (`PaymentTags`: `PaymentId`, `TxId`, `Uetr`, `Bic`, `PaymentDirection`, `Instant acceptedAt`, …) |
| `context` | `TraceContextCodec` (span context ↔ `traceparent` string), `Spans.childOf(traceparent)`, `Spans.linkedTo(traceparent)` |
| `http` | `ExternalBoundary` `RestClient` customizer that removes trace headers (§5.3); `InboundBoundaryPropagator`, which turns a foreign `traceparent` on the configured inbound paths into a link or ignores it |
| `export` | wrapped span exporter with a drop counter (`tpe.telemetry.spans.dropped`) |
| `test` | telemetry contract fixtures: Prometheus scrape assertions (exists, [20 §1](20-testing-strategy.md#1-test-levels)), in-memory span exporter with trace assertions, log capture with PII canaries |

### 4.1 Metrics

Unchanged from [00 §11](00-engineering-baseline.md#11-logging-tracing-metrics) and [25 §11](25-service-levels.md#11-metrics-to-add):
Micrometer, Prometheus registry, `/actuator/prometheus`. Additions:

- **Exemplars on.** Latency histograms carry the trace id of a sampled request as an exemplar (Spring Boot does this
  when tracing is present and the scrape negotiates OpenMetrics). This is the metric → trace link.
- **Names are the contract.** The name and labels a series has on `/actuator/prometheus` are exactly what Mimir
  stores and what the rules query (OBS-AC-02). No renaming, relabelling of metric names, or unit suffixing in the
  pipeline.
- **Label allow-list.** Each `tpe.*` meter declares its tag keys in one registry class; the telemetry contract test
  fails on any other key. `paymentId`, `txId`, `uetr`, IBANs, `clientId`, raw URIs and exception messages are never
  tags. `uri` uses the route template.
- **Self metrics:** `tpe.telemetry.spans.dropped` (counter), JVM, process, HikariCP, Kafka client and executor
  metrics from Spring Boot as is, except that `jvm_info` carries the JDK version as `jvm_version`: its own `version`
  tag would hide the common tag `version`. The Kafka client metrics keep their `client_id` tag: it names one of the
  service's consumers or producers, not the API client that the `clientId` rule above is about.

### 4.2 Traces

- Micrometer Observation with the OpenTelemetry bridge; OTLP/gRPC to the node-local Alloy agent
  (`OTEL_EXPORTER_OTLP_ENDPOINT=http://$(HOST_IP):4317`).
- Batch span processor: queue 8 192 spans, batch 512, schedule delay 1 s, export timeout 2 s. When the queue is full,
  spans are **dropped**, never blocking the caller (counted by `tpe.telemetry.spans.dropped`).
- Instrumented automatically: Spring MVC server, `RestClient`, Spring Kafka (only for the parent / link rule of §5;
  producer spans come from the relay), JDBC through datasource-micrometer with `CONNECTION` and `QUERY` spans,
  **no parameter values, no `FETCH` spans**.
- **Not traced** (observation predicates): `/actuator/**`, polling-worker iterations that found no due rows, relay
  iterations that found no rows, the relay heartbeat, Kafka consumer polls. A polling worker that handles rows
  creates one span per handled row. JDBC spans exist only inside another span: a query of a background loop with
  nothing to do (or of a gauge refresh) is not a trace of its own.
- Custom spans are listed in §5.2. Business attributes are set only through `PaymentTelemetry`; ArchUnit forbids
  `Span.tag(String, String)`, `Observation.highCardinalityKeyValue(…)` and `io.opentelemetry.api` outside
  `libs/observability` and `infrastructure` packages. `libs/messaging` sets the technical attributes of its own spans
  (§5.2: topic, key, event id, message type, ages, `tpe.inbox.duplicate`) itself; it runs no service ArchUnit rules.

### 4.3 Logs

- Unchanged: ECS JSON to stdout, no personal data at INFO and above ([00 §11](00-engineering-baseline.md#11-logging-tracing-metrics)).
- MDC keys: `paymentId`, `txId`, `uetr`, `commandId`, `eventId`, `traceId`, **`spanId`** (added). Handlers that
  continue a payment's trace (§5) also set the payment MDC keys, so log lines of a timer or a saga resume are
  findable by `paymentId`.
- Levels in production: `INFO` for `com.tpe`, `WARN` for frameworks. A log line is written per state transition, per
  external call outcome other than success, per retry and per DLT publish — not per successful internal call (the
  trace has those).
- Logs are not shipped by the application: stdout survives a pipeline outage and `kubectl logs` keeps working.
  Container log rotation is sized to hold at least 30 min of output per pod (kubelet `containerLogMaxSize`,
  `containerLogMaxFiles`), so an agent restart loses nothing.

### 4.4 Personal-data guard (in code)

1. Typed API: `PaymentTelemetry` accepts identifiers, never `Party`, `Iban` or names. `Iban.toString()` returns the
   masked form ([00 §11](00-engineering-baseline.md#11-logging-tracing-metrics)).
2. Request / response body logging and HTTP header capture are off for every endpoint and client.
3. Exception messages that may contain input (validation, XML parsing) are logged with the error code, not the
   message, at INFO and above.
4. Tests: the system tests use canary names and IBANs from `TestData`; the suite scans every collected log line and
   span attribute for them (INV-14, extended to spans by OBS-AC-09).

### 4.5 Configuration

| Property | Default | Notes |
|---|---|---|
| `tpe.observability.enabled` | `true` | `false` only in unit tests of other libraries |
| `management.tracing.sampling.probability` | `1.0` | §5.5 |
| `management.opentelemetry.tracing.export.otlp.endpoint` | `OTEL_EXPORTER_OTLP_ENDPOINT`, else `http://${HOST_IP}:4317`; none without either (spans stay in process) | OTLP/gRPC; compose: `http://alloy:4317` |
| `tpe.observability.trace.queue-size` / `.export-timeout` | `8192` / `2s` | drop, never block; a failed export (after the exporter's retries) counts as dropped |
| `tpe.observability.external-hosts` | from each client's base URL property | clients that strip trace headers (§5.3) |
| `tpe.observability.inbound.link-paths` / `.ignore-paths` | none | server paths whose caller `traceparent` becomes a link (customer channels) or is ignored (CSM, counterparties), §5.3 |
| `management.metrics.distribution.slo.*` | per [25 §11](25-service-levels.md#11-metrics-to-add) | |

## 5. Payment trace model

Rules from [ADR-014](../architecture/decisions/ADR-014-payment-trace-model.md): one trace per payment's automated
lifecycle; parent / child across `csm.*`; links for everything that outlives the payment or is done by a person.

### 5.1 Where the trace goes

Outgoing via CSM (`→` child, `⇢` link):

```
payment-api   POST /v1/payments (root; channel traceparent ⇢ link)
 ├─ vop-service   check reference
 └─ orchestrator  POST /v1/payments (submit) ── stores payments.trace_context
     └─ payment.saga.action ASSESS_RISK → risk-service; CREATE_HOLD → ledger-service
        └─ outbox row (headers.traceparent)
           └─ send csm.outbound.commands          (orchestrator relay)
              └─ process csm.outbound.commands     (csm-gateway) ── stores outgoing_transfers.trace_context
                 └─ csm.transmit pacs.008 (attempt n) → CSM HTTP client span
   CSM → POST /csm/v1/inbound (own short trace) ⇢ link
                 └─ csm.correlate pacs.002          (child of outgoing_transfers.trace_context)
                    └─ send csm.inbound.events → process csm.inbound.events (orchestrator)
                       └─ payment.saga.action SETTLE_HOLD → ledger-service
                          └─ payment.finalize       (tpe.payment.elapsed_ms = final − accepted)
                             └─ send payments.events ⇢ notification-service (new trace, link back)
```

On-us: the same up to the saga, then `SETTLE_ON_US` and `payment.finalize`. Timers (`OUTGOING_DEADLINE`,
`INVESTIGATION_RETRY`, `SETTLEMENT_OVERDUE`) and sweeper resumes start `payment.timer` / `payment.saga.action` spans as
children of `payments.trace_context` with `tpe.saga.resumed=true` when another instance picked the action up.

Incoming: the trace root is the gateway's server span for the `pacs.008` request (`incoming_transfers.trace_context`);
`send` / `process csm.inbound.events` → orchestrator (stores `payments.trace_context`) → risk, pending credit →
verdict → `send` / `process csm.outbound.commands` → `csm.answer pacs.002` → … settlement report correlated by
`csm.correlate pacs.002` → `CONFIRM_CREDIT` → `payment.finalize`.

Linked, new trace: `payments.events` and `accounts.events` consumers, DLT handlers, exception-case and back-office
API calls (the case's `payment_id` gives the link), sanctions runs (one span per run, one per chunk of 1 000
customers, one per hit).

### 5.2 Custom spans

| Span name | Kind | Service | Key attributes |
|---|---|---|---|
| `send <topic>` | PRODUCER | `messaging` relay | `messaging.destination.name`, `messaging.kafka.message.key`, `tpe.event.id`, `tpe.message.type`, `tpe.outbox.age_ms` (now − `created_at`) |
| `process <topic>` | CONSUMER | `messaging` listener | as above, `tpe.inbox.duplicate`; `tpe.outbox.age_ms` from `tpe-occurred-at` |
| `worker <name>` | INTERNAL | `messaging` polling worker | one per handled row, a new trace |
| `payment.saga.action` | INTERNAL | orchestrator | `tpe.saga.action`, `tpe.saga.attempt`, `tpe.saga.result`, `tpe.saga.resumed` |
| `payment.timer` | INTERNAL | orchestrator | `tpe.timer.type`, `tpe.timer.lateness_ms` (fire − `due_at`) |
| `payment.transition` | span event on the current span | orchestrator | `tpe.state.from`, `tpe.state.to`, `tpe.reason`, `tpe.cause` |
| `payment.finalize` | INTERNAL | orchestrator | `tpe.payment.state` (final), `tpe.reason`, `tpe.cause`, `tpe.payment.elapsed_ms` |
| `csm.transmit pacs.008` / `csm.answer pacs.002` / `csm.request pacs.028` | INTERNAL | gateway | `tpe.csm.attempt`, `tpe.csm.msg_id`, `tpe.send_by.margin_ms` or `tpe.answer_deadline.margin_ms`, `tpe.csm.outcome` |
| `csm.correlate <type>` | INTERNAL | gateway | `tpe.csm.correlated` (bool), `tpe.csm.msg_id` |
| `csm.deadline.answer` | INTERNAL | gateway | answer decided by `DEADLINE` (always kept, always an error status) |

Span status `ERROR` for technical failures only. A business refusal (`AM04`, `AC04`, a rule's `FR01`) is `OK` with
`tpe.cause=BUSINESS`, mirroring the good / bad split of [25 §1](25-service-levels.md#1-terms-and-rules).

### 5.3 Attributes and boundaries

| Attribute | Type | On | Note |
|---|---|---|---|
| `tpe.payment.id`, `tpe.tx.id`, `tpe.uetr` | string | every payment-path span | Tempo dedicated columns (fast lookup) |
| `tpe.debtor_agent.bic`, `tpe.direction`, `tpe.on_us` | string / bool | root and `payment.*` spans | BICs are not personal data |
| `tpe.payment.elapsed_ms` | int | every payment-path span | span start − `accepted_at` (incoming: gateway receipt) |
| `tpe.send_by.margin_ms`, `tpe.answer_deadline.margin_ms` | int | gateway spans | negative = late |
| `tpe.reason`, `tpe.cause` | string | rejections, `payment.finalize` | `cause` = `BUSINESS` \| `TECHNICAL` \| `EXTERNAL` |
| `tpe.probe` | bool | root spans | traffic of the probe client (§11) |

Never on a span: names, IBANs, remittance text, `endToEndId` (free text chosen by the customer), request or response
bodies, SQL parameter values, HTTP headers.

External boundary: clients to the CSM, counterparty VoP responders, webhook receivers and the sanctions provider use
the `ExternalBoundary` customizer — no `traceparent`, `tracestate` or `baggage`. Inbound from customer channels: a
foreign `traceparent` becomes a span link on the root span. Inbound from the CSM and counterparties: ignored.

### 5.4 The 10 s / 5 s budget view

The metrics of [25 §11](25-service-levels.md#11-metrics-to-add) show stage latency distributions; the trace shows one
payment. Together:

- **Payment lookup** dashboard (§9): enter `paymentId`, `txId` or `uetr` → TraceQL
  `{ span.tpe.payment.id = "$id" || span.tpe.tx.id = "$id" || span.tpe.uetr = "$id" }` → the trace waterfall with
  `tpe.payment.elapsed_ms` on each hop, plus the payment's log lines from Loki (`payment_id` structured metadata).
- **Slowest payments** panel: `{ name = "payment.finalize" && span.tpe.payment.elapsed_ms > 3000 }` over the last hour.
- **Closest to `sendBy`**: `{ name = "csm.transmit pacs.008" && span.tpe.send_by.margin_ms < 5000 }`.
- **Closest to the answer deadline**: `{ name = "csm.answer pacs.002" && span.tpe.answer_deadline.margin_ms < 3000 }`
  and every `csm.deadline.answer` span.
- Exemplars on `tpe.payment.stage.duration`, `tpe.csm.stage.duration` and `tpe.csm.answer.margin` open the trace of a
  payment from the slow bucket.

### 5.5 Sampling

**100 %** (`parentbased_always_on`), after the noise of §4.2 is removed. Every payment has a trace, which is what an
investigation, a complaint or a scheme query needs; at the target load this costs about 2 TB of object storage
(§14). SLIs never depend on traces (ADR-013 decision 5), so sampling could change without touching an SLO.

Revisit when sustained span ingest exceeds 20 000 spans/s or trace storage exceeds its budget: switch to tail
sampling in the Alloy gateway with trace-id-aware load balancing, keeping 100 % of traces with an `ERROR` span,
`tpe.cause=TECHNICAL`, `tpe.payment.elapsed_ms > 3000`, a `csm.deadline.answer` span or `tpe.probe=true`, and 10 %
of the rest.

## 6. Collection pipeline (Grafana Alloy)

Configuration in `deploy/observability/alloy/` (`agent.alloy`, `gateway.alloy`, shared modules); the same files run
in compose, the performance environment and production, with endpoints from environment variables.

### 6.1 Agent

DaemonSet, one per node.

| Input | Component | Processing | Output |
|---|---|---|---|
| OTLP from pods on the node | OTLP receiver (gRPC 4317, HTTP 4318) | memory limiter, batch | gateway (OTLP) |
| `/actuator/prometheus` of pods on the node | Kubernetes discovery (pods with annotation `tpe.io/scrape=true`, same node only), scrape 15 s, OpenMetrics, exemplars | target labels of §3 only | Mimir remote write (WAL) |
| Container stdout | file log receiver, container format parser | parse ECS JSON; `traceId` / `spanId` → log record trace context; `log.level` → severity; `paymentId`, `txId`, `uetr` → attributes | gateway (OTLP) |
| Node | node exporter (built in), `timex` collector on | — | Mimir remote write |

### 6.2 Gateway

Deployment, ≥ 2 replicas behind a Service.

1. Memory limiter (80 % of the container limit), then batch.
2. **Redaction backstop** (OTTL transform on log bodies and all span / log attributes):
   - flag then mask IBAN-shaped tokens: word-bounded, SEPA country code + 2 digits + 11–30 alphanumerics
     (`\b(AT|BE|BG|CH|CY|CZ|DE|DK|EE|ES|FI|FR|GB|GR|HR|HU|IE|IS|IT|LI|LT|LU|LV|MC|MT|NL|NO|PL|PT|RO|SE|SI|SK|SM)\d{2}[A-Z0-9]{11,30}\b`)
     → `tpe.redacted=true` on the record, token replaced by `<iban>`. `TxId`, `MsgId`, UUIDs and already masked IBANs
     (`DE89****3000`) do not match.
   - delete `http.request.header.*`, `http.response.header.*`, `url.query`, `db.query.parameter.*`.
   A flagged record is an INV-14 violation: the Loki ruler pages SEV-2 on any (§8.3). Names cannot be detected by a
   pattern; the code rules and canary tests of §4.4 cover them.
3. Tenant header per environment (`X-Scope-OrgID: tpe-<env>`), TLS and credentials to the backends.
4. Disk-backed sending queue per exporter (persistent volume), sized for 1 h of traffic at the target load (§14), so a
   backend outage up to an hour loses nothing.

Self-monitoring: Alloy's own metrics are scraped by the agent; alerts in §8.3.

## 7. Backends

All four run in the `observability` namespace on their own node pool, with object storage buckets per backend, from
the Grafana Helm charts (versions pinned in `deploy/observability/helm/*.values.yaml`; Loki ≥ 3.0 for OTLP ingest and
structured metadata). Tenants: `tpe-prod`, `tpe-staging`, `tpe-perf`.

| Backend | Mode at the target load | Retention | Limits / settings |
|---|---|---|---|
| Mimir | distributed, replication 3, zone-aware | 13 months (`compactor_blocks_retention_period`) | `max_global_series_per_user` 1 M (≈ 5× the estimate, §14), exemplars on (`max_global_exemplars_per_user` 100 000), ruler and Alertmanager enabled, Alertmanager 3 replicas |
| Loki | simple scalable, replication 3 | 30 d | labels only those of §3 plus `level`; `payment_id`, `tx_id`, `uetr`, `trace_id`, `span_id`, `version`, `tpe_redacted` as structured metadata; ruler enabled |
| Tempo | distributed, replication 3 | 14 d | dedicated attribute columns `tpe.payment.id`, `tpe.tx.id`, `tpe.uetr`, `tpe.cause`; metrics-generator with **service graphs only** (span metrics off: SLIs come from Micrometer) |
| Grafana | 2 replicas, PostgreSQL for its own state | — | OIDC login at the ADR-012 identity provider; dashboards and data sources provisioned read-only |
| Pyroscope | single binary, performance environment | 7 d | production only per ADR-013 |

Grafana data sources are provisioned with the correlations: Tempo → Loki (by trace id, ± 5 min), Tempo → Mimir
(service-graph and exemplar queries), Loki derived field `trace_id` → Tempo, Mimir exemplars → Tempo, Tempo →
Pyroscope (span profiles) where Pyroscope runs.

Access: role `TPE_OBSERVER` (all engineers) can view dashboards and query metrics and traces; Loki Explore needs
`TPE_ONCALL` because logs carry payment identifiers ([Q-27](40-open-questions.md#q-27-telemetry-retention-and-personal-data)).
Grafana Editor exists only in non-production instances; production dashboards change by pull request.

## 8. Alerting

### 8.1 Rule sources

```
deploy/slo/<service>.yml                 SLO definitions (25 §9.1), one file per service — source of truth
tools/slo-rules (Maven module)           generator: SLO files → Prometheus rules + SLO dashboards
deploy/observability/rules/generated/    output, committed; CI fails on drift (like the OpenAPI drift check)
deploy/observability/rules/cause/        hand-written cause alerts (25 §9.2) and meta alerts (§8.3)
deploy/observability/rules/loki/         LogQL rules (INV-14 redaction, §6.2)
deploy/observability/rules/tests/        promtool tests incl. recorded series of CH runs (20 §1, §5)
deploy/observability/alertmanager.yml    routing (§8.4)
deploy/observability/runbooks/<alert>.md one per paging alert
```

Sync: `mimirtool rules sync` and `mimirtool alertmanager load` from CI — on merge to `main` for `tpe-staging` and
`tpe-perf`, by the release job for `tpe-prod`. Rules are never edited in a UI.

Why our own generator instead of Sloth or Pyrra: 25 needs a minimum event count per window (§9.3), time-slice SLIs
("good minutes"), tier-based severity and the budget report of the performance runs from the same definitions. A
generator of a few hundred lines with golden-file tests is cheaper than bending a tool to all four.

### 8.2 SLO file and generated rules

```yaml
# deploy/slo/payment-orchestrator.yml
service: payment-orchestrator
tier: 0
team: payments-core
slos:
  - id: SLO-ORC-01
    description: payment submit available
    objective: 0.9999
    sli:
      events:
        selector: http_server_requests_seconds_count{application="payment-orchestrator",method="POST",uri="/v1/payments"}
        bad: outcome="SERVER_ERROR"
  - id: SLO-ORC-03
    description: accepted → SendCreditTransfer in outbox ≤ 1 s
    objective: 0.99
    sli:
      latency:
        histogram: tpe_payment_stage_duration_seconds{application="payment-orchestrator",stage="ACCEPTED_TO_SEND"}
        threshold: 1        # must be an existing bucket; the generator checks against the contract test output
  - id: SLO-ORC-09
    description: csm.inbound.events consumer lag ≤ 1 s
    objective: 0.999
    sli:
      timeslice:
        bad_when: max(tpe_consumer_lag_seconds{group="payment-orchestrator",topic="csm.inbound.events"}) > 1
```

Generated per SLO (label `slo="SLO-ORC-01"`, plus `service`, `tier`, `team`):

| Rule | Expression shape |
|---|---|
| `tpe:slo_events:rate5m`, `tpe:slo_bad:rate5m` | event SLIs: `sum(rate(...[5m]))`; latency: total − `le="<threshold>"` bucket; time-slice: one sample per minute, 1 for a bad minute |
| `tpe:slo_error_ratio:{5m,30m,1h,6h,3d}` | `sum_over_time(bad[w]) / sum_over_time(events[w])` from the 5 m series (cheap long windows) |
| `tpe:slo_budget_remaining:ratio30d` | `1 − (error ratio over 30 d) / (1 − objective)` — dashboards and the budget policy of [25 §10](25-service-levels.md#10-error-budget-policy) |
| alerts `SLOBurnFast`, `SLOBurnMedium`, `SLOBurnSlow` | the three rows of [25 §9.1](25-service-levels.md#91-burn-rate-alerts-every-slo-with-a-percentage); event SLIs also require `sum_over_time(events[short]) ≥ 100` |

Each alert gets `severity` (`page` \| `ticket`), `sev` (`SEV-1` \| `SEV-2` \| `SEV-3`, from tier and window per
[25 §10](25-service-levels.md#10-error-budget-policy)), and annotations `summary`, `runbook_url`, `dashboard_url`
(the SLO dashboard with the SLO pre-selected). CI rejects a paging rule without an existing runbook file.

### 8.3 Cause and meta alerts

The cause alerts of [25 §9.2](25-service-levels.md#92-cause-alerts-page-regardless-of-budget) are written by hand
in `rules/cause/`. This spec adds the alerts that keep the telemetry itself honest:

| Alert | Condition | Severity |
|---|---|---|
| `Watchdog` | always firing; routed to the external heartbeat, which pages when it stops arriving for 5 min | SEV-1 via heartbeat |
| `TargetDown` | `up == 0` for a tier-0 service instance for 1 min (the instance may still serve; we are blind) | SEV-2 |
| `TelemetryRedactedPII` | Loki: any record with `tpe_redacted="true"` in 5 min (INV-14) | SEV-2, data-protection officer informed |
| `SpansDropped` | `tpe.telemetry.spans.dropped` increases for 10 min on any instance | ticket |
| `AlloyQueueFilling` | gateway queue > 50 % of capacity | SEV-2 |
| `IngestRejected` | Mimir / Loki / Tempo rejecting writes (limits, errors) for 5 min | SEV-2 |
| `InvariantCheckStale` | `tpe_invariant_last_success_timestamp_seconds` older than 3 × the check interval (§12) | SEV-2 |
| `ProbeFailing` | a probe failed 3 consecutive runs (§11) | page, tier of the probed journey |

### 8.4 Routing

```
route: group_by [alertname, slo, service]
├─ alertname=Watchdog                       → heartbeat receiver (repeat 1 min)
├─ severity=page                            → paging tool, service per team (Q-28); repeat 1 h
│   └─ sev=SEV-1                            → also the incident chat channel
├─ severity=ticket                          → issue tracker webhook, team label; business hours
└─ default                                  → chat channel per team
inhibit: InvariantCheckStale suppresses nothing; TargetDown(service) suppresses SLOBurnSlow(service) only
```

Every page links a runbook with: what the alert means in payment terms, the first three queries or dashboards to
open, the safe mitigations (the recovery runbooks of [25 §8](25-service-levels.md#8-durability-and-recovery)), and
when to escalate to SEV-1.

## 9. Dashboards

Dashboards are JSON in `deploy/observability/grafana/dashboards/<folder>/`, provisioned read-only. SLO dashboards
are generated by `tools/slo-rules`; the rest are authored in a non-production Grafana, exported and committed.
Each has the variables `env`, `cluster` and, where it applies, `service`.

| Folder | Dashboard | Shows |
|---|---|---|
| Overview | **Engine overview** | CUJ-1 … CUJ-7 of [25 §3](25-service-levels.md#3-critical-user-journeys-and-end-to-end-budgets): status, budget remaining, traffic by direction; firing alerts; probe status; open exception cases |
| SLO | **SLO — <service>** (generated) | per SLO: SLI over time vs objective, budget remaining (30 d), burn rate 1 h / 6 h / 3 d, events counted |
| Payment path | **Outgoing — 10 s budget** | stage p50 / p99 stacked in the order of [25 §3.2](25-service-levels.md#32-outgoing-latency-budget-scheme-target-10-s-sendby-10-s-deadline-20-s) with exemplars; `sendBy` margin distribution; `AB03` count; slowest payments and closest-to-`sendBy` (TraceQL, §5.4) |
| Payment path | **Incoming — 5 s answer** | as [25 §3.3](25-service-levels.md#33-incoming-latency-budget-answer-deadline-5-s-verdict-give-up-4-s); `tpe.csm.answer.margin` heatmap; answers by `decidedBy`; `csm.deadline.answer` traces |
| Payment path | **Payment lookup** | §5.4: trace by id, logs by `payment_id`, state transitions as span events, links to linked traces |
| Payment path | **Rejections** | `tpe.payment.rejected` by direction, reason and `cause`; technical vs business vs external |
| Messaging | **Outbox, relay and consumers** | `tpe.outbox.pending`, publish latency, `tpe.relay.active` per service (one line must be 1), consumer lag seconds per topic / group, inbox duplicates, DLT by topic |
| Messaging | **Timers and workers** | `tpe.worker.lag` per worker vs SLO-ORC-08 / SLO-GW-07, timers fired by type |
| Ledger & compliance | **Ledger and invariants** | posting latency, holds and pending credits active, invariant check results and freshness (§12) |
| Ledger & compliance | **Sanctions** (tier 1) | list age, run status and duration by type, provisional freezes, open screening cases |
| Information | **Notifications** (tier 2) | dispatch delay, attempt lateness, deliveries by outcome, `payments.events` lag |
| Platform | **PostgreSQL**, **Kafka**, **Kubernetes**, **Time** | replication lag and sync standby, connections, locks; URP, ISR shrink, produce latency; pod restarts, CPU throttling; host clock offset |
| Platform | **Service graph** | Tempo service graph of the engine |
| Meta | **Observability health** | ingest rates, rejected writes, queue fill, dropped spans, rule evaluation failures, Watchdog |

Rules: a panel that shows an SLI uses the generated recording rule, never its own query; thresholds drawn on panels
come from the SLO file; no panel queries a high-cardinality label.

## 10. Platform exporters

| Exporter | Where | Feeds |
|---|---|---|
| **`tools/consumer-lag-exporter`** (new, small Spring Boot app, 2 replicas, one active by Kafka group membership) | engine namespace | `tpe_consumer_lag_seconds{topic,group,partition}` = now − timestamp of the record at the committed offset (read with a separate, non-committing consumer every 10 s); 0 when caught up. Exact semantics of [25 §11](25-service-levels.md#11-metrics-to-add); off-the-shelf exporters report offset lag or interpolate time. Also `tpe_consumer_lag_records`. |
| PostgreSQL exporter per database cluster | platform | replication lag, sync standby presence (SLO-PLT-01, "Synchronous standby missing"), connections, locks, `pg_stat_statements` top queries (normalised, no parameters) |
| Kafka: JMX exporter Java agent on brokers | platform | URP, ISR shrinks, request latency (SLO-PLT-02) |
| kube-state-metrics, kubelet / cAdvisor | platform | restarts, OOM kills, throttling, rollout state |
| Alloy node exporter (`timex`) | every node | `node_timex_offset_seconds` (SLO-PLT-04, "Clock offset") |
| `tools/invariant-checker` | engine namespace | §12 |

## 11. Synthetic probes

The probes of [25 §9.3](25-service-levels.md#93-low-traffic-and-synthetic-probes) are k6 scripts in
`deploy/observability/probes/`, run every 30 s / 60 s by a k6 runner **outside the engine's cluster** (separate
small cluster or VM in another zone), authenticated as OAuth2 client `tpe-probe`. Results go to Mimir by remote write
(`k6_*` metrics with label `probe`); the runner's own liveness is covered by `absent_over_time` on its metrics.
payment-api tags probe traffic `tpe.probe=true` on the root span from the `client_id`. The 0.01 EUR on-us probe runs in `perf`
and staging only ([Q-23](40-open-questions.md#q-23-synthetic-probes-in-production)).

## 12. Production invariant checks

The checks of [25 §6](25-service-levels.md#6-correctness-invariants-zero-budget) run in `tools/invariant-checker`, a
scheduled job that uses **the same implementation as the system tests** ([20 §3](20-testing-strategy.md#3-global-invariants)):
SQL through a read-only role against the **standby** of each database (never the primary), admin APIs across
services. It exposes `tpe_invariant_violations{check}` (gauge, last result), `tpe_invariant_last_success_timestamp_seconds{check}`
and `tpe_invariant_check_duration_seconds{check}`. Violations alert through the cause rules (SEV-1 / SEV-2 per 25 §6);
a check that stops running alerts as `InvariantCheckStale`. INV-14 is checked continuously by the gateway redaction
flag (§6.2) instead of a daily scan.

## 13. Environments

| Environment | Stack | Purpose |
|---|---|---|
| Local (`deploy/docker-compose.yml`, profile `observability`) | Alloy (agent config, compose discovery instead of Kubernetes), Mimir, Loki, Tempo single-binary, Grafana with provisioned data sources and dashboards; ports Grafana 3000, OTLP 4317 / 4318 | develop dashboards and spans; off by default to keep the stack light |
| CI system tests | same compose profile on `main` builds | OBS-AC-01 … -10: pipeline conformance, trace contract, PII canaries |
| Performance / chaos ([20 §5](20-testing-strategy.md#5-failure-injection-scenarios)) | production charts at small scale, tenant `tpe-perf`, Alertmanager routed to the receiver stub | rules evaluated exactly as in production; budget report; Pyroscope |
| Staging, production | §6, §7 | |

## 14. Capacity estimate

Upper bounds at 100 payments/s sustained around the clock (real traffic is lower off-peak); to be replaced by the
M5 performance-run measurements.

| Signal | Assumption | Rate | Stored |
|---|---|---|---|
| Metrics | ~25 service instances × ~4 000 series + ~100 000 platform series | ≈ 200 000 active series, 13 300 samples/s at 15 s | ≈ 2 GB/day, < 1 TB for 13 months |
| Traces | ≤ 60 spans per payment (outgoing via CSM; cap 80, OBS-AC-06) | 6 000 spans/s; 18 000 at peak | ≤ 300 B/span compressed → ≈ 150 GB/day, ≈ 2 TB for 14 d |
| Logs | ≤ 15 INFO lines × 600 B per payment, plus service noise | ≈ 1 MB/s | ≈ 10× compression → ≈ 8 GB/day, ≈ 250 GB for 30 d |
| Gateway disk queue | 1 h of traces + logs uncompressed | ≈ 25 GB per replica | persistent volume 50 GB |

## 15. Acceptance criteria

- **OBS-AC-01** Pipeline conformance (CI, compose `observability` profile): after one outgoing and one incoming
  payment, Mimir, Loki and Tempo each hold that run's data within 60 s.
- **OBS-AC-02** Every series named by the telemetry contract test exists in Mimir with the identical name and label
  set, including `le` buckets at every SLO threshold; no series in Mimir has a label outside §3 and the allow-list.
- **OBS-AC-03** A latency histogram sample in Mimir carries an exemplar whose trace id resolves in Tempo.
- **OBS-AC-04** A log line written inside a traced request is in Loki with `trace_id` and `payment_id` structured
  metadata, and the Grafana Tempo → Loki correlation finds it.
- **OBS-AC-05** Trace contract (component level, in-memory exporter): an outgoing payment via CSM forms **one** trace
  from the payment-api request to `payment.finalize`, containing `send` / `process` spans for both `csm.*` topics
  and the `csm.correlate pacs.002` span with a link to the inbound request; the `payments.events` consumer span is in
  another trace with a link to it. Same for an incoming payment from the `pacs.008` request.
- **OBS-AC-06** An outgoing payment trace has ≤ 80 spans; no span is created for empty polls or relay idle loops.
- **OBS-AC-07** A saga action resumed by another instance after a kill (CH-01) and a fired deadline timer are in the
  payment's trace with `tpe.saga.resumed=true` / `tpe.timer.lateness_ms`.
- **OBS-AC-08** No request to the simulator's CSM, VoP counterparty or webhook endpoints carries `traceparent`,
  `tracestate` or `baggage`; a `traceparent` sent by a channel to payment-api appears as a link, not as the parent.
- **OBS-AC-09** No `TestData` canary name or IBAN appears in any span attribute, span event or log line at INFO and
  above in the whole system-test run (INV-14 extended to traces).
- **OBS-AC-10** A log line containing an IBAN-shaped token reaches Loki masked with `tpe_redacted="true"`, and
  `TelemetryRedactedPII` fires (rule test).
- **OBS-AC-11** Isolation (performance environment, chaos run): with the Alloy gateway and Tempo stopped for 10 min
  under load, no tier-0 SLI moves and `tpe.telemetry.spans.dropped` rises; with the agent stopped for 10 min, no log
  line of the window is missing after it restarts.
- **OBS-AC-12** Stopping Mimir's ruler or Alertmanager in the performance environment makes the external heartbeat
  report the missing `Watchdog` within 5 min.
- **OBS-AC-13** Every paging rule has a runbook file and a dashboard link; the generated rules match the committed
  output (drift check); every SLO in `deploy/slo` has a panel in its generated dashboard.

## 16. Trade-offs and what to revisit

| Decision | Cost we accept | Revisit when |
|---|---|---|
| Self-hosted LGTM (ADR-013) | operating three stateful backends | Q-26 allows Grafana Cloud, or the platform team offers a managed stack |
| Pull metrics, OTLP traces | two transports, two failure modes | Micrometer OTLP and Mimir translation are proven name-identical (OBS-AC-02 would tell us) |
| 100 % trace sampling | ≈ 2 TB object storage at the target load | §5.5 thresholds |
| One trace per payment (ADR-014) | persisted context, re-parenting code in the gateway | recall (UC-11) adds a second lifecycle per payment |
| Own SLO rule generator | a small tool to maintain | Sloth / Pyrra support time-slice SLIs and event minimums |
| Logs via stdout tailing | depends on node log rotation sizing | log volume per node exceeds rotation capacity during an agent outage |
| Single site | the stack shares the site with the engine | the site / region ADR ([25 §13](25-service-levels.md#13-risks-and-what-to-revisit) R-7): the alert path then needs a second site first |

Also later: per-tenant cost dashboards, Grafana SLO reports e-mailed monthly to the SLA parties of
[25 §4](25-service-levels.md#4-slas), trace-derived critical-path analysis in the M5 performance report.
