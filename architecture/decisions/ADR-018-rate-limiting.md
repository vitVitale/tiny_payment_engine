# ADR-018 Rate limiting: local token buckets in the services

- **Status:** Accepted — figures pending sign-off by channel teams and fraud prevention
- **Date:** 2026-10-22
- **Deciders:** architecture (mechanism, decided); channel teams (per-client figures), fraud prevention (per-customer
  figures)
- **Resolves:** [Q-33](../../specs/40-open-questions.md#q-33-rate-limits), requirement SEC-04 of
  [specs/00 §12.3](../../specs/00-engineering-baseline.md#123-production-requirements)

## Context
Nothing limits request rates. Three abuse cases matter:

- **Name harvesting.** `POST /v1/payee-verifications` returns the account holder's actual name for a close match, so
  a customer (or a stolen token) can collect names for many IBANs.
- **Payment flooding.** A misbehaving channel or a compromised customer token can fill the payment path; the engine
  is sized for 300 payments/s at peak ([Q-13](../../specs/40-open-questions.md#q-13-target-load)).
- **Responder abuse.** The VoP scheme responder answers any PSP in the bank directory; until mTLS is checked,
  `requestingPspBic` is self-asserted.

Constraints: no shared cache in the stack (PostgreSQL and Kafka only); `POST /v1/payments` has a p99 budget of
300 ms and the reference check 50 ms; limits are about abuse, not billing, so exact counts are not needed.

## Decision
- **Where:** in the services, after authentication and before any database access (in payment-api before the
  idempotency guard, so a refused request leaves no key behind). The ingress keeps only coarse DoS protection
  (connections and request size per source), run by the platform.
- **How:** in-memory token buckets per instance (Bucket4j core, pinned in the parent POM as it is not in the Spring
  Boot BOM). Configured limits are **per instance**; with round-robin load balancing the effective limit is the
  configured value × instances. Buckets for inactive keys expire after their refill period, so memory stays bounded.
- **Keys and initial figures** (configuration, changeable without a release):

  | Limit | Service | Key | Initial value |
  |---|---|---|---|
  | All requests of a channel | payment-api | `client_id` | 2× the channel's share of 300/s, set per channel (`tpe.api.rate-limit.clients.<client_id>`) |
  | Payment initiation | payment-api | `sub` | 30 per minute |
  | Payee verification | payment-api | `sub` | 20 per 10 min and 100 per day |
  | Scheme responder | vop-service | requesting PSP | 50/s |

  The `sub` limits are divided by the instance count in the deployed configuration (`tpe.api.rate-limit.instances`),
  so a customer gets roughly the stated figure whichever instance answers.
- **Refusal:** `429 RATE_LIMITED` with `Retry-After` (seconds until the next token), problem body per
  [00 §7](../../specs/00-engineering-baseline.md#7-rest-conventions). A `sub` reaching its daily VoP limit is logged as a
  security event (SEC-06).
- **Responder identity:** in `prod`, vop-service takes the requesting PSP from the mTLS client certificate and
  refuses a body `requestingPspBic` that differs (`401 UNKNOWN_PARTICIPANT`); the limit is keyed by the certificate
  identity.
- **Metrics:** `tpe.ratelimit.refused{service,limit}` (no `sub` or `client_id` tag — high cardinality).

## Options considered
| Option | Accuracy | Payment-path cost | New infrastructure | Assessment |
|---|---|---|---|---|
| A. Ingress / API gateway only | exact per gateway | none in services | gateway plugin with JWT parsing | Sees `client_id` only if it parses the JWT; per-`sub` limits and the VoP daily limit need claims the platform gateway does not read today. |
| **B. Local token buckets in the services** | approximate (× instances) | in-memory, microseconds | none | Chosen: enough for abuse limits, no new dependency on the payment path. |
| C. Shared buckets in Redis | exact | one network call per request | Redis cluster, tier 0 | A new tier-0 store for counters that need not be exact. |
| D. Counters in PostgreSQL | exact | one write per request | none | Adds a write per request to the payment path and contention on hot keys. |

## Trade-off analysis
B gives up exactness: a customer whose requests are spread over N instances may get somewhat more than the
configured limit, and a restart resets buckets. For abuse limits that is acceptable — the goal is to make harvesting
and flooding slow, not to count precisely. In exchange there is no new store, no network hop and no new failure
mode: a limiter cannot be "down". C or D become worth their cost only if limits ever become contractual (billing,
scheme-mandated quotas).

## Consequences
- New configuration in [10 §6](../../specs/10-payment-api.md#6-configuration) and
  [13 §7](../../specs/13-vop-service.md#7-configuration); `429 RATE_LIMITED` in the payment-api error table.
- Synthetic probes (`tpe-probe`) and system tests run with their own per-client limits; performance tests raise the
  per-client limit of the load client explicitly, so a forgotten limit cannot hide in a load result.
- Revisit: C if channels need exact quotas, or if the VoP scheme rules prescribe per-PSP quotas with reporting.

## Action items
1. [ ] Rate-limit filter in payment-api and vop-service (responder), with the configuration above.
2. [ ] Tests: `429` above each limit (SEC-04, [20 §1](../../specs/20-testing-strategy.md#1-test-levels)); a refused
   initiation leaves no idempotency key.
3. [ ] Channel teams confirm per-client figures; fraud prevention confirms per-customer figures.
