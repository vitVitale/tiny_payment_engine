# ADR-017 Secrets, keys and encryption at rest

- **Status:** Accepted — pending sign-off by security and platform
- **Date:** 2026-10-22
- **Deciders:** architecture (decided); security, platform (sign-off before M5); secret owners (rotation intervals)
- **Resolves:** [Q-32](../../specs/40-open-questions.md#q-32-secret-store-and-rotation),
  [Q-36](../../specs/40-open-questions.md#q-36-encryption-at-rest-and-key-management), requirements SEC-03 and SEC-07 of
  [specs/00 §12.3](../../specs/00-engineering-baseline.md#123-production-requirements)

## Context
Secrets reach services as environment variables ([00 §12.2](../../specs/00-engineering-baseline.md#122-data-handling)).
Environment variables show up in `/actuator/env`, crash dumps and `kubectl describe`, and change only with a restart.
Nothing defines where secrets are stored, who reads them, or how they rotate. Two secrets protect data that outlives a
rotation: the webhook encryption key (subscription secrets are stored encrypted, `secret_key_id` already exists in
[16 §5](../../specs/16-notification-service.md#5-data-model)) and the VoP pepper (13 months of `responder_audit` hashes,
[13 §5](../../specs/13-vop-service.md#5-data-model)).

At rest, the databases hold holder names, dates of birth and ISO 20022 XML; backups (PITR 30 d), Kafka log directories
and telemetry object storage hold the same or derived data. Community PostgreSQL has no transparent data encryption.

## Decision
### Secrets
- **Store:** the platform's central secret manager (HashiCorp Vault in this design). External Secrets Operator syncs
  each service's secrets into a Kubernetes Secret in its namespace; etcd encrypts Secrets with a KMS provider.
- **Delivery:** mounted as files under `/run/secrets/tpe/` and read with Spring Boot's
  `spring.config.import=optional:configtree:/run/secrets/tpe/`. In `prod`, secrets in environment variables are a
  startup failure, and `/actuator/env` and `/actuator/configprops` are disabled.
- **Access:** each service's Vault policy reads only its own path `secret/tpe/<artifactId>/*`. Humans have no read
  access to production secrets; rotation runs as a pipeline job.
- **Inventory and rotation** (no downtime in any case):

  | Secret | Owner | Interval | Procedure |
  |---|---|---|---|
  | Webhook encryption key | notification-service team | 12 months | keyring `tpe.notification.secret-keys` (id → key) and `tpe.notification.active-key-id`; new writes use the active key; a `PollingWorker` re-encrypts rows with older ids; a key is removed once no row uses it |
  | VoP audit pepper | vop-service team | 12 months | peppers `tpe.vop.audit-peppers` (version → pepper) and `tpe.vop.active-pepper-version`; each `responder_audit` row stores `pepper_version`; evidence lookups hash with the row's version; a pepper is removed after its last row expires (13 months) |
  | OAuth2 client secrets (ADR-012) | each service team | 90 days | the IdP holds two valid secrets per client during rotation |
  | PostgreSQL and Kafka credentials | platform | 90 days | two users per service (`-a` / `-b`, [ADR-015](ADR-015-kafka-authentication-and-acls.md)), switched by rolling deploy, then the old one is reset |
  | CSM / VoP mTLS keystores | csm-gateway, vop-service teams | per counterparty certificate | renewed before expiry; alert 30 d before |
  | Internal TLS certificates ([ADR-016](ADR-016-tls-inside-the-cluster.md)) | platform | 30 days, automatic | cert-manager; alert 7 d before expiry |

### Encryption at rest
- **Storage-level encryption** with keys in the platform KMS, rotated yearly: PostgreSQL volumes, WAL archive and
  base backups, Kafka log directories, object storage buckets (telemetry, backups), etcd.
- **Application-level encryption only where storage encryption does not help:** webhook signing secrets (AES-GCM, as
  today) and VoP responder audit (peppered hashes, as today). No column encryption of holder data.
- Backups never leave the bank's infrastructure unencrypted; restore drills
  ([25 §8](../../specs/25-service-levels.md#8-durability-and-recovery)) include fetching the backup key from the KMS.

## Options considered
### Secret delivery
| Option | Complexity | Rotation | App dependency | Assessment |
|---|---|---|---|---|
| A. Environment variables (today) | none | restart; values leak into dumps and actuator | none | Not acceptable for production. |
| B. Kubernetes Secrets only (sealed in Git) | low | manual, per cluster | none | No central audit, no access policy beyond namespaces. |
| **C. Vault + External Secrets, mounted files** | low–medium | central, audited; config tree re-read on restart, keyrings allow overlap | none (files) | Chosen: services stay unaware of Vault. |
| D. Spring Cloud Vault in each service | medium | dynamic, leases | Vault on the startup path of every service | Couples service start to Vault availability; more code to test. |

### Encryption at rest
| Option | Protects against | Cost | Assessment |
|---|---|---|---|
| **A. Storage-level with KMS keys** | lost disks, backup copies, storage-admin access | none in code | Chosen. |
| B. A + column encryption of holder data | additionally DB users reading tables | breaks VoP and sanctions matching queries (they compare names); key handling in three services | Little extra protection: the services that would hold the keys are the only DB users. |

## Trade-off analysis
C keeps secret handling out of application code: services read files, and keyrings let old and new keys overlap, so
rotation is a configuration change plus a background job. The price is two platform components (Vault, External
Secrets) that must be available at deploy time, not at run time: running pods keep their mounted files during a
Vault outage. For data at rest, storage encryption covers the realistic threats (media, backups, infrastructure
staff); column encryption would mainly protect against our own services and would cost the matching features.

## Consequences
- [13 §5](../../specs/13-vop-service.md#5-data-model) gains `responder_audit.pepper_version`; [16 §7](../../specs/16-notification-service.md#7-configuration)
  and [13 §7](../../specs/13-vop-service.md#7-configuration) use keyrings instead of single values.
- Each service's `prod` profile checks at startup that no secret comes from the environment.
- Revisit: dynamic database credentials from Vault (option D) if 90-day rotation proves too slow; column encryption if
  backups or replicas ever leave the bank's infrastructure.

## Action items
1. [ ] Platform: Vault paths and policies per service, External Secrets resources, etcd KMS encryption, encrypted
   storage classes and buckets.
2. [ ] Keyring support in notification-service (re-encrypt worker) and vop-service (`pepper_version`).
3. [ ] Rotation runbooks per secret in `deploy/observability/runbooks/`; a rotation of each secret rehearsed in staging.
4. [ ] `prod` startup checks: no environment secrets; actuator `env` / `configprops` disabled.
