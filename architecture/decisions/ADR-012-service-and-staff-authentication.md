# ADR-012 Authentication between services and for staff

- **Status:** Accepted
- **Date:** 2026-10-21
- **Amended:** 2026-10-22 — refresh at 50 % of the token lifetime (was 80 %, aligned with
  [25 §13](../../specs/25-service-levels.md#13-risks-and-what-to-revisit) R-3); scope table completed with every internal
  REST caller of [20 §10.1](../../specs/20-testing-strategy.md#101-integration-inventory) and the operational clients;
  service-token lifetime 30 min instead of 5 min ([Q-24](../../specs/40-open-questions.md#q-24-service-token-lifetime-and-idp-availability),
  pending sign-off by security)
- **Resolves:** [Q-10](../../specs/40-open-questions.md#q-10-service-to-service-and-back-office-authentication), the authentication part of the
  "Security" gap in the [architecture README](../README.md#cross-cutting-concerns-not-yet-designed)

## Context
Customer channels authenticate with OAuth2 access tokens at payment-api. Inside the engine, every REST call is
currently trusted because it comes from the internal network. Some internal endpoints are dangerous in the wrong hands:

- ledger-service: settle / release holds, block and unblock accounts (including lifting sanctions blocks);
- payment-orchestrator: submit payments, resolve exception cases;
- risk-service: confirm or dismiss sanctions cases.

Staff (back-office operators, compliance officers) also call these services. Every action must be attributable to a
person (UC-10 audit), and sanctions blocks may be lifted only by risk-service or a compliance officer.

Constraints: the payment path has a 1 s internal budget, so authentication must not add a network hop per call; the
team knows Spring Security; there is no service mesh today.

## Decision
- **Services authenticate with OAuth2 client-credentials JWTs** from the same identity provider as the channels.
  - Each service has its own client. Tokens are cached and refreshed before expiry (lifetime 30 min, Q-24), so there is no
    extra call per request.
  - The receiving service validates the JWT locally (cached JWKS) and checks `aud` = its own name and a **scope per
    capability**:

    | Receiver | Scope | Granted to |
    |---|---|---|
    | payment-orchestrator | `payments:submit`, `payments:read` | payment-api |
    | ledger-service | `ledger:postings` | payment-orchestrator |
    | ledger-service | `ledger:sanctions-block` (block / unblock with `SANCTIONS*` reasons) | risk-service |
    | ledger-service | `ledger:customer-accounts` (`GET /v1/customers/{customerId}/accounts`) | risk-service |
    | risk-service | `risk:assess` | payment-orchestrator |
    | vop-service | `vop:verify` (verify and reference check) | payment-api |
    | every service | `ops:read` (read-only admin endpoints) | `tpe-invariant-checker` |

  Operational and test clients:
  - `tpe-probe` (synthetic probes, [26 §11](../../specs/26-observability.md#11-synthetic-probes)) is a customer-API client
    of payment-api with `payments:read` and `vop:verify`, and an `accounts` claim holding only the probe accounts.
    `payments:write` only if [Q-23](../../specs/40-open-questions.md#q-23-synthetic-probes-in-production) approves the
    on-us probe.
  - `tpe-invariant-checker` ([26 §12](../../specs/26-observability.md#12-production-invariant-checks)) reads admin
    endpoints with `ops:read`; it can never call a mutating endpoint.
  - System tests (pact P-10) mint staff tokens with roles, and channel tokens, from the mock issuer in `local` and
    `systemtest`. No such client exists in `prod`.

- **Staff authenticate with OIDC** (authorisation code + PKCE in the back-office UI or CLI) at the same identity provider;
  tokens carry `roles` (`BACKOFFICE_OPERATOR`, `COMPLIANCE_OFFICER`). Services check roles per endpoint and record
  `sub` in audit logs. Lifting a `SANCTIONS` block requires `COMPLIANCE_OFFICER` or the `ledger:sanctions-block`
  scope.
- **Network isolation stays** as a second layer (Kubernetes NetworkPolicy: only the listed callers can reach each service).
- **mTLS** is used at the scheme boundaries only (CSM, VoP counterparties), as already designed.
- **Rollout:** milestones M0–M4 run with network isolation only and `@InternalApi` markers. Token checks are switched
  on in M5 behind a property (`tpe.security.internal-auth.enabled`) and are mandatory in the `prod` profile.

## Options considered
| Option | Complexity | Latency | Identity of caller | Team familiarity | Assessment |
|---|---|---|---|---|---|
| A. Network isolation only | Low | none | none | high | A compromised pod can move money. Not acceptable for production. |
| B. mTLS via a service mesh (Istio / Linkerd) | High (new platform component) | small | service identity (SPIFFE) | low | Strong, but needs a mesh to operate and does not cover staff users. |
| C. Application-level mTLS (Spring SSL bundles, own CA) | Medium | small | service identity from the certificate | medium | Certificate issuing and rotation become our job. |
| **D. OAuth2 client-credentials JWT + network policy** | Low–medium | none per call (cached token, local validation) | service and scopes | high (same stack as the channel API) | One mechanism for services and staff, fine-grained scopes. |

## Trade-off analysis
D does not encrypt traffic by itself; inside the cluster, TLS on service endpoints (or a later mesh) adds that. B is
stronger on transport but heavier to run and still needs D-like authorisation for staff. D gives the most control per
endpoint for the least new infrastructure, and B can be added later without changing application code.

## Consequences
- Every internal client gets a token interceptor; every internal controller declares the required scope or role.
- The identity provider becomes a dependency of the payment path at token refresh time. Tokens are refreshed at 50 %
  of their lifetime and the old token is used until the refresh succeeds, so an IdP outage shorter than half the
  lifetime does not stop payments; refresh failures alert ([25 §13](../../specs/25-service-levels.md#13-risks-and-what-to-revisit)
  R-3). With a 30 min lifetime the engine rides through an IdP outage of 15 min (CH-16), so the IdP stays at
  SLO-PLT-03 99.9 % instead of becoming tier 0. A leaked service token is usable for at most 30 min, only against
  its `aud` and scopes, and travels only over TLS ([ADR-016](ADR-016-tls-inside-the-cluster.md)).
- Not covered here: Kafka authentication and ACLs ([ADR-015](ADR-015-kafka-authentication-and-acls.md)), TLS inside the
  cluster ([ADR-016](ADR-016-tls-inside-the-cluster.md)), secrets ([ADR-017](ADR-017-secrets-keys-and-encryption-at-rest.md))
  and rate limits ([ADR-018](ADR-018-rate-limiting.md)).
- Revisit: four-eyes approval for exception resolution and sanctions dismissal
  ([Q-17](../../specs/40-open-questions.md#q-17-exception-resolution-controls)).

## Action items
1. [ ] Register clients and scopes in the mock OAuth2 issuer for local and system tests, including `tpe-probe` and
   `tpe-invariant-checker`.
2. [ ] Shared Spring Security configuration for internal resource servers and clients.
3. [ ] NetworkPolicy manifests when deployment manifests are written.
4. [ ] Switch `tpe.security.internal-auth.enabled` on in M5; system tests run with it.
