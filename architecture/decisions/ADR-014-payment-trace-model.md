# ADR-014 Payment trace model: one trace per payment lifecycle

- **Status:** Proposed
- **Date:** 2026-10-22
- **Resolves:** the "per-payment tracing against the 10 s budget" part of the Observability gap in the
  [architecture README](../README.md#cross-cutting-concerns-not-yet-designed). Amends the tracing rule of
  [specs/00 §11](../../specs/00-engineering-baseline.md#11-logging-tracing-metrics). Build details:
  [specs/26 §5](../../specs/26-observability.md#5-payment-trace-model).

## Context
An outgoing payment crosses payment-api, vop-service, payment-orchestrator, risk-service, ledger-service, two Kafka
topics, csm-gateway and the CSM before it is final; an incoming one goes the other way. The scheme gives it 10 s
(target) and 20 s (deadline); the incoming answer has 5 s ([specs/25 §3.2, §3.3](../../specs/25-service-levels.md#32-outgoing-latency-budget-scheme-target-10-s-sendby-10-s-deadline-20-s)).

The baseline says: one trace per synchronous flow, **links** across Kafka hops. That gives four to six separate
traces per payment. Answering "where did the 7 s of payment X go?" then means finding each trace by attribute and
lining them up by hand, which is the investigation an on-call engineer needs during an `AB03` or a `DEADLINE`
answer. Metrics show that a stage is slow, not which payment and why.

Complications:

- Work continues after the request that started it: the saga runner after the submit response
  ([ADR-008](ADR-008-resumable-saga-steps.md)), the relay after the commit ([ADR-006](ADR-006-ordered-outbox-relay.md)),
  timers 20 s later, a different instance after a crash.
- The CSM does not propagate our trace context: a `pacs.002` arrives as a new HTTP request and is matched to its
  transfer by `TxId` only after parsing.
- Some consequences of a payment live much longer than the payment: webhook retries for 24 h, exception cases
  resolved by people hours later.
- External parties must not receive our trace ids, and must not be able to steer our traces.

## Decision
**A payment's automated lifecycle is one trace**: from acceptance (outgoing: payment-api request; incoming: `pacs.008`
receipt) to its final state or an exception case.

1. **Parent / child across the CSM loop.** Consumers of `csm.outbound.commands` and `csm.inbound.events` start their
   processing span as a **child** of the context in the message's `traceparent` header. The relay creates one
   `send <topic>` producer span per message, child of the context stored with the outbox row, and puts its own
   context into the header.
2. **Persisted context.** The payment's context is stored where the work resumes: `payments.trace_context`
   (orchestrator) and `outgoing_transfers.trace_context` / `incoming_transfers.trace_context` (gateway), as a W3C
   `traceparent` string. Saga actions, sweeper resumes, timers and transmissions start their spans as children of it.
3. **Re-parent on correlation.** An inbound CSM request (`pacs.002`, `pacs.028` answer) gets its own short server
   trace. As soon as the gateway has matched it to a transfer, the processing span is a child of the transfer's
   stored context, with a span link to the inbound request span.
4. **Linked, not continued.** These start a new trace with a span link to the payment's context: consumers of
   `payments.events` (notifications) and `accounts.events`, DLT handling and replays, anything an operator or
   compliance officer does (exception cases, unblock), sanctions runs.
5. **Trust boundary.** No `traceparent`, `tracestate` or `baggage` header is sent to the CSM, counterparty VoP
   responders, webhook receivers or the sanctions provider. Incoming trace headers from outside the engine never
   become a parent; payment-api records a channel's `traceparent` as a span link so channel teams can correlate.
   W3C baggage is not used inside the engine either: every message already carries `paymentId` and `txId`.
6. **Budget on every span.** Payment-path spans carry `tpe.payment.elapsed_ms` (span start − `accepted_at`) and, where
   a scheme deadline applies, `tpe.send_by.margin_ms` or `tpe.answer_deadline.margin_ms`. One `payment.finalize`
   span per payment records the end-to-end figure.

## Options considered
| Option | Per-payment view | Trace size / length | Complexity | Assessment |
|---|---|---|---|---|
| A. Links across every Kafka hop (current baseline) | 4–6 traces, stitched by TraceQL search on `tpe.payment.id` | small, short | lowest | The waterfall of one trace never shows the 10 s budget; the gap stays open. |
| B. Parent / child everywhere | one trace | unbounded: webhook retries 24 h, human actions days later | low | Mixes lifecycles; traces that never end; notification noise inside payment traces. |
| **C. One trace per automated lifecycle, links outside it** | one trace for the critical path; follow-up work one link away | typically < 3 s and ≤ 80 spans; minutes during an investigation | medium: persisted context, re-parenting in the gateway | Chosen. |
| D. No per-payment tracing; use `payment_transitions` as the timeline | state timeline per payment, authoritative | — | none | Shows *when* states changed, not *where* the time went across services. Kept as the authoritative record; traces complement it. |

## Trade-off analysis
C needs three columns, a parent rule in the `messaging` consumer and relay, and an explicit re-parenting step in the
gateway's correlation code. In return, one trace id answers "what happened to this payment, where did the time go,
and how close did it get to `sendBy`". Traces stay bounded because the long-lived and human parts are links. The
risk is a trace that spans an investigation (minutes): Tempo assembles late spans by trace id, and search still finds
it by `tpe.payment.id`.

## Consequences
- [specs/00 §11](../../specs/00-engineering-baseline.md#11-logging-tracing-metrics) tracing rule replaced by a reference
  to this ADR; `messaging` sets the parent rule per topic (configuration, not code in each service).
- Flyway: nullable `trace_context VARCHAR(55)` on `payments`, `outgoing_transfers`, `incoming_transfers` (expand-only,
  [00 §9](../../specs/00-engineering-baseline.md#9-database-and-migrations)). Missing context (rows from before the
  migration) starts a new root span.
- The 100 % sampling of [specs/26 §5.5](../../specs/26-observability.md#55-sampling) is what makes "every payment has a
  trace" true; if sampling is ever introduced it must keep whole payment traces (decision at the root, carried in
  `traceparent`).
- Span timestamps come from different hosts: skew up to 100 ms ([00 §6](../../specs/00-engineering-baseline.md#6-time))
  can show a child starting before its parent. Budget attributes use the deciding component's clock, as deadlines do.
- Revisit: when recall (UC-11) arrives, a recall is its own lifecycle and gets its own trace linked to the payment.

## Action items
1. [ ] `libs/observability`: `TraceContextCodec` (span context ↔ `traceparent` string), typed payment attributes.
2. [ ] `messaging`: per-topic consumer parent rule (`CHILD` for `csm.*`, `LINK` otherwise), relay producer spans.
3. [ ] Orchestrator and gateway: `trace_context` columns, spans for saga actions, timers, transmissions, correlation.
4. [ ] External clients: strip trace headers (CSM, VoP counterparty, webhooks, sanctions provider).
5. [ ] Trace contract test (OBS-AC-05 … -08 in specs/26).
