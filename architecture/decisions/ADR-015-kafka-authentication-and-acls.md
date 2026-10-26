# ADR-015 Kafka authentication and per-topic ACLs

- **Status:** Accepted — pending sign-off by security and platform
- **Date:** 2026-10-22
- **Deciders:** architecture (decided); security, platform (sign-off before M5)
- **Resolves:** [Q-30](../../specs/40-open-questions.md#q-30-kafka-authentication-and-authorisation),
  requirement SEC-01 of [specs/00 §12.3](../../specs/00-engineering-baseline.md#123-production-requirements)

## Context
[ADR-012](ADR-012-service-and-staff-authentication.md) protects REST calls. Kafka carries the facts and commands that
move money, and today any client that reaches a broker may read or write any topic:

- writing `csm.outbound.commands` makes csm-gateway send a `pacs.008`;
- writing `csm.inbound.events` settles or rejects a payment in the orchestrator;
- writing `accounts.events` changes the holder data that VoP answers and sanctions screening rely on;
- reading `accounts.events` exposes every customer's name and date of birth.

NetworkPolicy cannot narrow this: every service talks to the brokers. Constraints: Kafka 4.x in KRaft mode (standard
ACL authorizer); the payment path has a 1 s internal budget and must not gain a dependency on the identity provider
per connection ([25 §13](../../specs/25-service-levels.md#13-risks-and-what-to-revisit) R-3); the team knows Spring
Kafka; there is no service mesh.

## Decision
- **Client listener `SASL_SSL` with SCRAM-SHA-512.** Server-side TLS uses the internal CA of
  [ADR-016](ADR-016-tls-inside-the-cluster.md). Inter-broker and controller listeners are the platform's concern and
  use mTLS with broker principals.
- **One principal per service:** `svc-<artifactId>` (for example `svc-csm-gateway`). For rotation without downtime each
  service has two users, `-a` and `-b`, with identical ACLs ([ADR-017](ADR-017-secrets-keys-and-encryption-at-rest.md)).
  Operational principals: `svc-kafka-init` (create topics and ACLs only), `svc-consumer-lag-exporter` (describe
  groups and topics), `ops-dlt` (read `*.dlt`).
- **Deny by default:** `allow.everyone.if.no.acl.found=false`; super users are the broker principals only.
- **ACLs follow the topic table of [02 §2](../../specs/02-messaging-contracts.md#2-topics)**:

  | Principal role | ACLs |
  |---|---|
  | producer of a topic | `WRITE`, `DESCRIBE` on that topic |
  | consumer of a topic | `READ`, `DESCRIBE` on that topic; `READ` on its own group `<service>` (literal) |
  | consumer of a topic | `WRITE`, `DESCRIBE` on `<topic>.dlt` (the `messaging` DLT handler) |
  | `ops-dlt` | `READ`, `DESCRIBE` on each `<topic>.dlt` (literal; Kafka has no suffix patterns, so the generator lists them); group `ops-dlt` |

  Result for the payment topics: only payment-orchestrator writes `csm.outbound.commands` and `payments.events`; only
  csm-gateway writes `csm.inbound.events`; only ledger-service writes `accounts.events`, and only
  vop-service and risk-service read it.
- **Single source:** topics and ACLs are declared in `deploy/kafka/topics.yml` and applied by the `kafka-init` step;
  a configuration-consistency test ([20 §1](../../specs/20-testing-strategy.md#1-test-levels)) checks the file against
  the topic table of 02 §2.
- **Profiles:** `local` stays `PLAINTEXT` without ACLs. `systemtest` uses `SASL_PLAINTEXT` with SCRAM and the same
  ACLs, so a missing ACL fails the system tests. `perf`, staging and `prod` use `SASL_SSL`.

## Options considered
| Option | Complexity | Payment-path dependency | Identity | Team familiarity | Assessment |
|---|---|---|---|---|---|
| A. Network isolation only | none | none | none | high | Any compromised pod can send money. Not acceptable. |
| **B. SASL/SCRAM-SHA-512 over TLS** | low | none (credentials verified by the brokers) | principal per service | high (Spring Kafka properties) | Chosen. Credentials stored hashed in KRaft metadata; rotation by two users per service. |
| C. SASL/OAUTHBEARER with the ADR-012 clients | medium | IdP at connect and every re-authentication (`connections.max.reauth.ms`) | same client as REST | medium | One identity system, but makes the IdP a tier-0 dependency of Kafka too, which Q-24 just avoided. |
| D. mTLS (`SSL`) principals | medium–high | none | certificate subject | low | Strong; needs client certificates per service, which ADR-016 deliberately does not issue. |

## Trade-off analysis
B and D both keep the IdP off the Kafka path; B needs no client certificates and is a property change in Spring
Kafka, so it fits ADR-016's server-side-only TLS. Its cost is a second credential per service (the SCRAM password
next to the OAuth2 client secret) and a rotation procedure we own. C would unify identities but trade that for an
availability coupling the payment path cannot afford. If the platform later issues workload certificates (for
example with a mesh), D replaces B without touching ACLs: only the principal names change.

## Consequences
- `messaging` gets the SASL / TLS client properties; services set only their credentials.
- The DLT handler can write only the DLTs of topics the service consumes; an operations tool needs `ops-dlt`.
- A new consumer or producer is a change to `deploy/kafka/topics.yml` and 02 §2 in the same pull request.
- Compose gains SCRAM users for the `systemtest` profile; their passwords live in the compose `.env` of the test
  stack, never in production configuration.
- Revisit: C if the IdP becomes tier 0 anyway; D if workload certificates become available.

## Action items
1. [ ] `deploy/kafka/topics.yml` with topics, principals and ACLs; `kafka-init` applies it.
2. [ ] Consistency test: `topics.yml` ⇔ 02 §2 table.
3. [ ] `systemtest` compose with SCRAM and ACLs; system test asserting that a service principal cannot produce to a
   topic it does not own (SEC-01, [20 §1](../../specs/20-testing-strategy.md#1-test-levels)).
4. [ ] `SASL_SSL` listener and user provisioning for `perf`, staging and `prod` (platform).
