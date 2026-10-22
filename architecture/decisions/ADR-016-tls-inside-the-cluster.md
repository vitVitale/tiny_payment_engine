# ADR-016 TLS inside the cluster: server-side TLS from an internal CA

- **Status:** Accepted — pending sign-off by security and platform
- **Date:** 2026-10-22
- **Deciders:** architecture (decided); security, platform (sign-off before M5)
- **Resolves:** [Q-31](../../specs/40-open-questions.md#q-31-tls-inside-the-cluster), requirement SEC-02 of
  [specs/00 §12.3](../../specs/00-engineering-baseline.md#123-production-requirements); the "TLS inside the cluster"
  revisit item of [ADR-012](ADR-012-service-and-staff-authentication.md)

## Context
ADR-012 authenticates every internal caller with a JWT but leaves traffic in clear. Inside the cluster travel:
bearer tokens (a captured service token is valid until it expires, 30 min after
[Q-24](../../specs/40-open-questions.md#q-24-service-token-lifetime-and-idp-availability)), holder names and dates of
birth (account API, VoP, `accounts.events`), and payment data. PostgreSQL connection security is not specified.
Constraints: no service mesh today; 1 s internal budget on the payment path; Spring Boot 4 supports SSL bundles with
certificate reload; the platform runs cert-manager.

## Decision
- **Every service HTTP endpoint serves TLS only** in `perf`, staging and `prod`, on the existing ports
  ([00 §13](../../specs/00-engineering-baseline.md#13-local-development)), management endpoints included.
- **Certificates:** cert-manager issues them from the internal CA `tpe-internal-ca`: ECDSA P-256, lifetime 30 d,
  renewed at 2/3 of the lifetime, DNS names = the Kubernetes service names. Mounted as files; Spring Boot PEM SSL
  bundles with `reload-on-update`, so renewal needs no restart.
- **Clients** (`RestClient` with JDK `HttpClient`) trust only `tpe-internal-ca`, verify host names and reuse
  connections (keep-alive). The `prod` profile refuses `http://` base URLs for internal clients at startup, like the
  existing simulator-URL check.
- **No client certificates.** Caller identity stays with the ADR-012 JWT; TLS adds confidentiality, integrity and
  server authentication.
- **PostgreSQL:** `sslmode=verify-full` against the database server CA. **Kafka:** `SASL_SSL`
  ([ADR-015](ADR-015-kafka-authentication-and-acls.md)). **Telemetry:** Alloy to backends over TLS
  ([specs/26 §6.2](../../specs/26-observability.md#62-gateway)); Alloy scrapes services over TLS with the internal CA.
- **Profiles:** `local`, `test` and `systemtest` stay on plain HTTP; the TLS path is exercised in `perf` (M5
  performance and chaos runs).

## Options considered
| Option | Complexity | Latency | Identity | Operations | Assessment |
|---|---|---|---|---|---|
| A. No encryption, NetworkPolicy only | none | none | — | none | Tokens and personal data readable by anyone who can sniff node traffic. Not acceptable for production. |
| **B. Server-side TLS, cert-manager, internal CA** | low–medium | one handshake per pooled connection | server only (JWT for callers) | cert-manager already run by the platform | Chosen. |
| C. Service mesh with mTLS (Istio ambient, Linkerd) | high: new platform component | small, sidecar or node proxy | workload identity (SPIFFE) | new control plane on the payment path | Strongest, but a new tier-0 component; ADR-012 already rejected it for now. |
| D. Application mTLS (client certificates per service) | medium–high | as B | service from certificate | client certificate issuing and rotation | Duplicates the identity ADR-012 already gives; [Q-24](../../specs/40-open-questions.md#q-24-service-token-lifetime-and-idp-availability) option (c) rejected for the same reason. |

## Trade-off analysis
B closes the confidentiality gap with tools the team and platform already run, and keeps one identity mechanism. The
handshake cost (about 1–2 ms with P-256) is paid per pooled connection, not per payment, so the 1 s budget is not
affected at steady state; the M5 performance run confirms it after rolling restarts, when pools are cold. C would add
caller identity at the transport layer, which we do not need while JWTs carry it, at the price of a new control
plane. Because B is configuration only, C can replace it later without code changes.

## Consequences
- Every service gets an SSL bundle `tpe-server` and a trust bundle `tpe-internal`; `RestClient` builders in each
  service use them.
- Certificate expiry is monitored: alert 7 d before expiry of any mounted certificate (cert-manager metrics), so a
  stuck renewal is noticed long before it breaks calls.
- Health probes and Alloy scrape over HTTPS.
- Revisit: C when the platform offers a mesh, or if a regulator requires mutual authentication on every hop.

## Action items
1. [ ] Platform: `tpe-internal-ca` issuer and `Certificate` resources per service.
2. [ ] Shared SSL bundle configuration in the parent's Spring Boot defaults; `prod` startup check for `http://`.
3. [ ] PostgreSQL `verify-full` in `perf`, staging and `prod` data sources.
4. [ ] M5 performance run with TLS on: SLO-API-02 and the 1 s internal budget hold after a rolling restart.
