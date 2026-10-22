# ADR-013 Telemetry stack: OpenTelemetry instrumentation, Grafana Alloy, Mimir, Loki, Tempo

- **Status:** Accepted (2026-10-22, [Q-29](../../specs/40-open-questions.md#q-29-telemetry-stack-and-payment-trace-model); self-hosted, [Q-26](../../specs/40-open-questions.md#q-26-telemetry-hosting) decided)
- **Date:** 2026-10-22
- **Resolves:** the "production telemetry stack" part of the Observability gap in the
  [architecture README](../README.md#cross-cutting-concerns-not-yet-designed). Build details:
  [specs/26](../../specs/26-observability.md).

## Context
[specs/25](../../specs/25-service-levels.md) defines what the engine must measure: about 60 SLOs, multi-window
burn-rate alerts written in PromQL, cause alerts, synthetic probes and production invariant checks. The services
already emit Micrometer metrics on `/actuator/prometheus`, traces through Micrometer Tracing with an OpenTelemetry
bridge, and ECS JSON logs on stdout ([00 §11](../../specs/00-engineering-baseline.md#11-logging-tracing-metrics)).
Nothing yet says where that telemetry goes, how it is stored, or who evaluates the alerts.

Forces:

- **Not on the payment path.** A telemetry outage must never slow or fail a payment; SCT Inst runs 24/7.
- **Exact names.** The SLI queries of `deploy/slo/<service>.yml`, the `promtool` rule tests and the telemetry
  contract test all use the Prometheus names served by `/actuator/prometheus`
  (`http_server_requests_seconds_bucket{le="0.3"}`). The pipeline must not rename anything.
- **Personal data.** Names, IBANs and remittance text must not reach telemetry at INFO and above (INV-14). The
  pipeline needs a second line of defence after the code rules.
- **Regulated operator.** A bank's telemetry holds pseudonymous payment identifiers; outsourcing it to a SaaS
  provider is an outsourcing decision under DORA with data-residency questions
  ([Q-26](../../specs/40-open-questions.md#q-26-telemetry-hosting)).
- **Small team.** One stack, one query language per signal, configuration in the repository.
- The project chose the OpenTelemetry and Grafana ecosystems.

## Decision
1. **Instrumentation stays Micrometer + Micrometer Tracing with the OpenTelemetry bridge**, no OpenTelemetry Java
   agent. Shared setup lives in a new library `libs/observability` (Spring Boot auto-configuration). OTLP is the
   wire protocol for traces; logs stay on stdout; metrics stay pull-based on `/actuator/prometheus` in OpenMetrics
   format with exemplars.
2. **Collector: Grafana Alloy**, two tiers:
   - **agent** (DaemonSet): receives OTLP from pods on its node, scrapes their `/actuator/prometheus`, tails
     container stdout, runs the node exporter (including clock offset).
   - **gateway** (Deployment, ≥ 2 replicas): traces and logs pass through it for PII redaction, tenant headers and
     a disk-backed retry queue. Metrics go from the agents straight to Mimir with remote write (the agent's
     write-ahead log buffers them).
3. **Backends, self-hosted, object storage (S3-compatible):** Mimir (metrics, rule evaluation, Alertmanager),
   Loki 3 (logs, OTLP ingest, structured metadata), Tempo (traces, TraceQL, service graphs), Grafana (dashboards,
   provisioned from the repository). Pyroscope (continuous profiling) in the performance environment from M5, in
   production only if its measured overhead is ≤ 2 % CPU.
4. **Alerts are Prometheus rule files evaluated by the Mimir ruler** (and the Loki ruler for log-based rules), routed by
   Mimir's Alertmanager. Rules are generated from the SLO files and synced from the repository; Grafana-managed alert
   rules are not used.
5. **SLIs come only from application metrics**, never from span metrics or logs: they must not depend on sampling
   or on the trace pipeline being up.
6. **Separate failure domain:** the observability stack runs in its own namespace and node pool (separate cluster when
   one exists), and an always-firing *Watchdog* alert feeds an external heartbeat check, so a dead alert pipeline
   pages someone.

## Options considered

### Overall stack
| Option | Fit with specs/25 | Operations | Data residency | Cost | Assessment |
|---|---|---|---|---|---|
| A. Prometheus + Jaeger + OpenSearch | PromQL rules work as written | three unrelated systems, three UIs, weak correlation between signals | self-hosted | medium | Works, but trace↔log↔metric correlation is hand-made and the project chose Grafana. |
| B. Grafana Cloud (managed LGTM) | same as C | lowest: nothing to run | provider's region; DORA outsourcing contract | per series / GB, grows with volume | Fastest start, but a DORA outsourcing arrangement; not chosen (Q-26). Config in C ports to B by changing endpoints. |
| **C. Self-hosted Grafana LGTM + Alloy** | PromQL rules, `promtool` tests and `mimirtool` sync as written | medium: three stateful backends on object storage | in-house | infrastructure only | Chosen. Same configuration shape as B, so moving later is cheap. |
| D. Commercial APM (Datadog, Dynatrace) | rules rewritten in the vendor's language | lowest | vendor | highest | Proprietary query and alert languages break the "one SLO file → rules + tests" chain of specs/25. |

### Collector
| Option | Assessment |
|---|---|
| OpenTelemetry Collector (contrib) | Vendor-neutral. Metrics scraped by its Prometheus receiver are translated to the OTLP model and back; unit suffixes, `_total` and exemplars need careful flags, which is exactly the renaming risk we want to avoid. |
| **Grafana Alloy** | An OpenTelemetry Collector distribution that also contains the native Prometheus scrape / remote-write pipeline: metric names and exemplars arrive in Mimir byte-identical to `/actuator/prometheus`, while traces and logs use the same OTel components as the upstream collector. Cost: Alloy's own configuration syntax and a Grafana dependency in the pipeline (not in the services). |

### Metrics transport
| Option | Assessment |
|---|---|
| Micrometer OTLP registry (push) | One protocol for all signals, but names are derived by Mimir's OTLP translation, the telemetry contract test would test a format that is not what alerts query, and a dead instance is silent instead of `up == 0`. |
| **Prometheus pull (scrape)** | Names are the contract end to end; `up` is a free liveness signal; histogram buckets at the SLO thresholds stay exact. Cost: two transports (pull for metrics, OTLP for traces). |

### Instrumentation
| Option | Assessment |
|---|---|
| OpenTelemetry Java agent | Broad auto-instrumentation, but bytecode manipulation on the payment path, a second instrumentation of what Spring already observes, and upgrades decoupled from the Spring Boot BOM. |
| **Micrometer Observation + OTel bridge** | Already in the baseline; Spring MVC, `RestClient`, Spring Kafka and JDBC (datasource-micrometer) are covered; versions come from the BOM. The relay, timers and gateway correlation need hand-written spans anyway ([ADR-014](ADR-014-payment-trace-model.md)). |

## Trade-off analysis
C costs us the operation of three stateful backends; in return the alert chain of specs/25 (SLO file → generated
PromQL → `promtool` tests → evaluation) runs unchanged, data stays in-house, and B stays available as a
configuration change if Q-26 is ever reopened. Alloy trades a little vendor neutrality *inside the pipeline* for
exact metric names; the services speak only OTLP and Prometheus exposition, so replacing Alloy with the upstream
collector later is a pipeline change, not an application change.

## Consequences
- New module `libs/observability`; `messaging` depends on it for relay and consumer spans.
- New repository content: `deploy/observability/` (Alloy, backend values, Grafana provisioning, rules, Alertmanager,
  runbooks, probes) and `tools/` (SLO rule generator, consumer-lag exporter, invariant checker).
- The local compose stack gets an `observability` profile running the same components as production, so the
  pipeline is testable in CI (OBS-AC-01).
- The observability stack is itself monitored and has an SLO (SLO-PLT-05, SLO-PLT-06 in
  [specs/25 §5.9](../../specs/25-service-levels.md#59-platform)).
- Revisit: native histograms once Micrometer and Mimir support them for SLO buckets; tail sampling when trace volume
  exceeds the sizing of [specs/26 §14](../../specs/26-observability.md#14-capacity-estimate); Grafana Cloud if Q-26
  is reopened.

## Action items
1. [ ] M0: `libs/observability`, compose `observability` profile, pipeline conformance test (OBS-AC-01 … -04).
2. [ ] M0: Alloy agent and gateway configuration shared by compose, performance environment and production.
3. [ ] M5: Helm values for Mimir, Loki, Tempo, Grafana, Alertmanager; object storage buckets; retention per
   [specs/26 §7](../../specs/26-observability.md#7-backends).
4. [ ] M5: SLO rule generator, dashboards, runbooks, probes, consumer-lag exporter, invariant checker.
