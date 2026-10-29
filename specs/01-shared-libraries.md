# 01 Shared libraries

ADR-001 limits shared code to four thin libraries. Rule of thumb: a library may contain **types and plumbing**,
never a business decision that belongs to one service. If only one service uses something, it lives in that service.

## 1. `payment-domain`

Plain Java, no dependencies. Everything is immutable; constructors validate and throw `IllegalArgumentException`
with a machine-readable code.

### 1.1 Value objects

| Type | Rules |
|---|---|
| `Money(long minor, Currency currency)` | `minor > 0` unless created via `Money.zero`; only `EUR`; `plus`, `minus`, `compareTo`; `parse("123.45")` accepts 1–12 integer digits and 0–2 decimals, rejects exponent / sign / spaces; `toDecimalString()` always two decimals. |
| `Iban` | Upper-cased, spaces removed; length per country table (SEPA countries only); ISO 7064 mod 97-10 check. Exposes `countryCode()`, `bankCode()` (per-country offset table), `masked()`. |
| `Bic` | 8 or 11 chars `[A-Z]{4}[A-Z]{2}[A-Z0-9]{2}([A-Z0-9]{3})?`; `bic8()` for comparisons. |
| `TxId`, `EndToEndId`, `MsgId` | ISO `Max35Text`, SEPA character set; `EndToEndId` accepts `NOTPROVIDED`. |
| `Uetr` | UUID v4 string, lower case. |
| `PaymentId`, `CommandId` | wrap `UUID`; `CommandId.derive(clientId, idempotencyKey)` implements the UUID v5 rule of [00 §4](00-engineering-baseline.md#4-identifiers). |
| `PartyName` | 1–70 chars after trim, SEPA character set. `toString()` returns `"PartyName[***]"` so names never leak into logs. |
| `RemittanceInformation` | Unstructured, ≤ 140 chars, SEPA character set. |

**SEPA character set:** `a-z A-Z 0-9 / - ? : ( ) . , ' +` and space (EPC basic Latin). Validation rejects anything else;
no silent transliteration at the API boundary.

### 1.2 Reason codes

`enum ReasonCode` with exactly the codes in the [glossary](../architecture/glossary.md#reason-codes-externalstatusreason1code-used-in-this-design)
plus `MS03` (reason not specified — used for internal technical failures, see [Q-01](40-open-questions.md#q-01-reason-code-for-internal-technical-failures)).
Each constant carries its ISO description. `ReasonCode.parse` accepts unknown ISO codes coming from the CSM as
`ReasonCode.Other(String)` (sealed interface `StatusReason = ReasonCode | Other`) so we never drop a reason we
received.

### 1.3 Ids and time helpers

- `Ulid` generator (monotonic within the same millisecond), `UuidV7` generator. Both take a `Clock` and a
  `RandomGenerator` for testability.
- `MutableClock` (test fixture jar `payment-domain:tests`): `advance(Duration)`, `set(Instant)`.

### 1.4 Bank directory

`payment-domain` holds the `BankDirectory` interface, `BankEntry` (bank code, BIC, `sctInstReachable`, optional VoP
endpoint) and `InMemoryBankDirectory`, built from the entries. Construction rejects (`INVALID_BANK_DIRECTORY`) a
directory without an entry for `ownBankCode`, duplicate bank codes, and an `ownBic` that differs from the own bank's
entry. `payment-domain` stays free of non-JDK dependencies (00 §1), so loading the file is Spring configuration in each
consuming service: a `@ConfigurationProperties("tpe.bank-directory")` class bound from the YAML file at
`tpe.bank-directory.location` (default `classpath:bank-directory.yml`, overridden in compose with
`deploy/config/bank-directory.yml`), turned into an `InMemoryBankDirectory` bean. The file keeps this shape under the
`tpe.bank-directory` key; `BankDirectoryFileTest` (system-tests) checks the compose file is a valid directory:

```yaml
tpe:
  bank-directory:
    ownBankCode: "10010010"
    ownBic: "TPEBDEFFXXX"
    banks:
      - bankCode: "10010010"
        bic: "TPEBDEFFXXX"
        sctInstReachable: true
        vopEndpoint: null              # own bank: answered locally
      - bankCode: "20020020"
        bic: "PEERDEFFXXX"
        sctInstReachable: true
        vopEndpoint: "http://csm-simulator:8090/vop/v1/verifications"
```

Operations: `lookup(Iban) → Optional<BankEntry>`, `isOwn(Iban)`, `ownBic()`. A bank matches an IBAN by the bank code
inside it and by country: bank codes are national (a `PL` IBAN can carry the 8 digits of a `DE` bank code), so the
IBAN's country must equal the country of the bank's BIC (characters 5–6). Used by
payment-api (reachability validation), payment-orchestrator (on-us detection, creditor agent BIC) and vop-service
(routing). It is static configuration, reloaded on restart only.

## 2. `iso20022-model`

### 2.1 Message versions

| Message | Version | Used by |
|---|---|---|
| `head.001` | `head.001.001.02` | csm-gateway, csm-simulator |
| `pain.001` | `pain.001.001.09` | payment-api |
| `pain.002` | `pain.002.001.10` | payment-api, notification-service |
| `pacs.008` | `pacs.008.001.08` | csm-gateway, csm-simulator |
| `pacs.002` | `pacs.002.001.10` | csm-gateway, csm-simulator |
| `pacs.028` | `pacs.028.001.03` | csm-gateway, csm-simulator |
| `camt.054` | `camt.054.001.08` | notification-service |

Versions follow the EPC SCT Inst implementation guidelines. Later messages (`pacs.004`, `camt.056`, `camt.029`,
`camt.053`) are added with UC-11 / UC-12.

### 2.2 Contents

- `src/main/resources/xsd/` — the ISO 20022 XSDs (from iso20022.org) plus EPC SCT Inst restricted XSDs where available.
  The EPC restricted XSDs are not added yet; they replace the ISO XSD of their version without code changes.
  iso20022.org refused automated downloads, so the XSDs were taken from public mirrors; each file is byte-identical
  (line endings aside) across three independent copies and carries the ISO Standards Editor header.
- JAXB classes generated at build time (`org.jvnet.jaxb:jaxb-maven-plugin`), one Java package per message version,
  e.g. `com.tpe.iso20022model.pacs008v08`. Generated sources are not committed. `equals` / `hashCode` are generated
  as plain code (`-XsimpleEquals`, `-XsimpleHashCode`), with no runtime dependency on the JAXB plugins. Limitation:
  `xs:any` content (`SplmtryData`, the header's `Sgntr`) is a DOM node compared by identity, so a document carrying
  it is never equal to its round trip. The documents we produce leave both out.
- `IsoCodec` — `String marshal(Object document)`, `<T> T unmarshal(String xml, Class<T> type)`; JAXB contexts are
  created once and cached; marshallers are not thread-safe and are created per call or pooled.
- `XsdValidator` — validates a raw XML string against the schema of its namespace before unmarshalling; returns a
  list of violations (line, column, message). Must reject DOCTYPE / external entities (XXE): the parser is
  configured with `disallow-doctype-decl = true`.
- **Envelope** for the CSM transport (simulated): `urn:tpe:csm:envelope:1`

  ```xml
  <Envelope xmlns="urn:tpe:csm:envelope:1">
    <AppHdr xmlns="urn:iso:std:iso:20022:tech:xsd:head.001.001.02">…</AppHdr>
    <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">…</Document>
  </Envelope>
  ```

  `EnvelopeCodec` splits / joins header and document and validates both. The envelope XSD
  (`src/main/resources/envelope/csm-envelope.1.xsd`) fixes the structure only and skips the parts; each part is
  validated against the XSD of its namespace. The document's namespace selects its `CsmMessageType` (`pacs.008`,
  `pacs.002`, `pacs.028`); any other namespace is rejected as `UNSUPPORTED_NAMESPACE`. `split` returns the parts or
  the first invalid part (envelope, header, document) with its violations — a missing header or document is
  reported as that part; `join` throws on an invalid part.
- Small **builders** for the documents we produce (`Pacs008Builder`, `Pacs002Builder`, `Pacs028Builder`,
  `Pain002Builder`, `Camt054Builder`) that take `payment-domain` types, so services never fill JAXB trees by hand.
  Builders set only fields required by the EPC guidelines; defaults: `ChrgBr = SLEV`, `SvcLvl/Cd = SEPA`,
  `LclInstrm/Cd = INST`, `SttlmMtd = CLRG`, `NbOfTxs = 1` (we never batch).
- No business logic: deciding *which* status or reason to put in a message is the caller's job.

Acceptance:

- **ISO-AC-01** Every builder output validates against its XSD (property-based test over generated domain inputs).
- **ISO-AC-02** A document with a DOCTYPE declaration is rejected by `XsdValidator` without resolving entities.
- **ISO-AC-03** Round trip `unmarshal(marshal(x))` equals `x` for golden samples of every supported message.

## 3. `messaging`

Implements the transactional outbox, inbox, DLT handling and polling workers of
[ADR-002](../architecture/decisions/ADR-002-rest-plus-kafka-with-outbox.md). Spring Boot auto-configuration,
enabled by `tpe.messaging.enabled=true`.

### 3.1 Tables (Flyway `classpath:db/migration/messaging`)

```sql
CREATE TABLE outbox (
  id            BIGSERIAL PRIMARY KEY,
  event_id      UUID        NOT NULL UNIQUE,
  topic         VARCHAR(100) NOT NULL,
  message_key   VARCHAR(100) NOT NULL,
  message_type  VARCHAR(100) NOT NULL,
  schema_version INT        NOT NULL,
  payload       JSONB       NOT NULL,
  headers       JSONB       NOT NULL DEFAULT '{}',
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  published_at  TIMESTAMPTZ
);
CREATE INDEX outbox_unpublished ON outbox (id) WHERE published_at IS NULL;

CREATE TABLE inbox (
  dedup_key     VARCHAR(200) PRIMARY KEY,
  topic         VARCHAR(100) NOT NULL,
  event_id      UUID        NOT NULL,
  processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

Published outbox rows are deleted after `tpe.messaging.outbox.retention` (default 7 d); inbox rows after
`tpe.messaging.inbox.retention` (default 8 d, MUST be longer than Kafka topic retention of consumed topics — 7 d,
[02 §2](02-messaging-contracts.md#2-topics) — so a replayed message is still deduplicated). The compacted
`accounts.events` is exempt: its consumers deduplicate by `revision`, which holds for any replay.

### 3.2 Outbox writer

```java
public interface OutboxWriter {
  /** MUST be called inside an active transaction; throws IllegalStateException otherwise. */
  UUID write(String topic, String key, Message message);
}
```

`Message` is the sealed root of all records in `contracts` (each declares its `type` and `schemaVersion`). The
writer serialises with the shared `ObjectMapper`, generates `eventId` (UUID v7), adds headers (§3.4), inserts the
row, and issues `NOTIFY tpe_outbox` (delivered at commit by PostgreSQL).

### 3.3 Outbox relay

- **Single active relay per service** ([ADR-006](../architecture/decisions/ADR-006-ordered-outbox-relay.md)). Each
  instance tries `pg_try_advisory_lock(hashtext('<service>-outbox'))` on a dedicated connection that is never returned
  to the pool; only the holder publishes. Others retry every 1 s. Reason: two relays publishing different batches
  concurrently could reorder messages of the same key (e.g. `SendCreditTransfer` before `RequestPaymentStatus`),
  which ADR-004 relies on.
- **Bounded failover** ([ADR-006 amendment](../architecture/decisions/ADR-006-ordered-outbox-relay.md#amendment-2026-10-22-bounded-failover)):
  the lock connection runs `SET idle_session_timeout = '3s'`; the leader sends `SELECT 1` on it every 1 s, but only
  while its publish loop has completed an iteration (a failed send with back-off counts) within the last 2 s. A stalled
  loop stops heartbeating, closes the lock connection and reports not live. Before each batch the leader checks that
  its last heartbeat succeeded; if not, it stops publishing and rejoins as a follower on a new connection. Gauge
  `tpe.relay.active` is 1 on the leader. Config: `tpe.messaging.outbox.lock-session-timeout` (`3s`),
  `.heartbeat-interval` (`1s`), `.takeover-retry` (`1s`), `.stall-after` (`2s`).
- **No skipping:** the relay MUST read from the oldest unpublished row in `id` order — no `SKIP LOCKED`, no
  relay-local offset. An old and a new leader overlapping after a failover then produce duplicates, never reordering.
- **Visibility window:** `BIGSERIAL` ids can commit out of order, so the relay reads only rows with
  `created_at < now() − 20 ms` (configurable `tpe.messaging.outbox.visibility-window`). A row that commits later than
  that is still published, just after rows with higher ids of *other* keys; rows of the same key are written by
  successive transactions and keep their order.
- Loop: wait for `LISTEN tpe_outbox` notification or 100 ms poll timeout → `SELECT … WHERE published_at IS NULL
  ORDER BY id LIMIT 200` → send all with the Kafka producer → wait for all acks → `UPDATE … SET published_at =
  now() WHERE id = ANY(:ids)`.
- If any send in a batch fails, the batch stops at the first failure (later rows are not marked) and the loop retries
  from the oldest unpublished row with back-off 50 ms → 1 s. Re-publishing an already-sent row is allowed (consumers
  deduplicate); skipping one is not.
- Producer settings: `acks=all`, `enable.idempotence=true`, `max.in.flight.requests.per.connection=5`,
  `linger.ms=0`, `delivery.timeout.ms=10000`, `request.timeout.ms=5000` (Kafka requires `delivery.timeout.ms` ≥
  `linger.ms` + `request.timeout.ms`; the default 30 s would not start), `compression.type=lz4`.
- Target: insert → Kafka ack p99 < 50 ms (metric `tpe.outbox.publish.latency`).

### 3.4 Headers

| Header | Value |
|---|---|
| `tpe-event-id` | UUID v7 |
| `tpe-message-type` | e.g. `SendCreditTransfer` |
| `tpe-schema-version` | integer |
| `tpe-occurred-at` | ISO instant of the outbox insert |
| `tpe-producer` | service name |
| `traceparent` | W3C trace context of the transaction that wrote the row; the relay replaces it with its `send <topic>` span. Consumers of `csm.*` continue it as parent, all others link to it ([ADR-014](../architecture/decisions/ADR-014-payment-trace-model.md)) |

### 3.5 Polling worker

```java
public interface DueItemHandler<T> {
  /** Runs inside the polling transaction while the row is locked. Must not do remote I/O. */
  void handle(T item);
}
```

`PollingWorker<T>` executes the `FOR UPDATE SKIP LOCKED` loop of [00 §10](00-engineering-baseline.md#10-persistent-timers-and-polling-workers)
for a given SQL query + row mapper + handler; configurable batch size, poll interval and thread count; exposes
`tpe.worker.lag{worker}`. Used by: orchestrator timers and saga sweeper, csm-gateway answer deadlines and send
retries, notification retries, ledger snapshot job.

### 3.6 Consumers, inbox and DLT

- Listener container: manual ack (`AckMode.RECORD`), `enable.auto.commit=false`, `isolation.level=read_committed`,
  `max.poll.records=50`, concurrency = partitions assigned per instance.
- `InboxGuard.firstTime(String dedupKey, String topic, UUID eventId) → boolean` inserts into `inbox` with
  `ON CONFLICT DO NOTHING`; MUST be called in the same transaction as the business change. If it returns `false`
  the handler returns without side effects (metric `tpe.inbox.duplicates`).
- Error classification by `TpeErrorHandler` (a configured `DefaultErrorHandler`):
  - **Non-retryable** — `DeserializationException`, `ContractViolationException` (thrown by `contracts` validation),
    `UnknownMessageTypeException` (a `tpe-message-type` the listener does not accept, or none; thrown by
    `ConsumedMessages.readOneOf`): retried 3 times with 100 ms back-off, then published to `<topic>.dlt` with headers
    `tpe-dlt-reason`, `tpe-dlt-exception`, `tpe-dlt-original-offset`, `tpe-dlt-original-partition`, and committed.
  - **Retryable** — everything else (DB unavailable, optimistic-lock conflict): retried in place with exponential
    back-off 100 ms → 5 s, indefinitely. The partition is blocked, which is intended: a consumer that cannot write its
    DB cannot make progress anyway, and skipping would break per-key ordering.
- Every DLT publish increments `tpe.dlt.messages{topic}` (alert rule: any increase). Services may register a
  `DltHook` to react (orchestrator opens an exception case for `csm.*` DLTs).
- Listeners MUST NOT make remote REST calls. They validate, deduplicate, record the fact, and commit.

### 3.7 Acceptance

- **MSG-AC-01** A row written with `OutboxWriter` in a rolled-back transaction is never published.
- **MSG-AC-02** Killing the relay between Kafka ack and `published_at` update leads to a duplicate publish, never a loss.
- **MSG-AC-03** With two service instances, messages with the same key are published in insert order (test: 1 000
  interleaved inserts for 10 keys, assert per-key order on the consumer).
- **MSG-AC-04** A message that fails deserialisation lands on `<topic>.dlt` after 3 attempts and the next message on
  the partition is processed.
- **MSG-AC-05** A handler failing with a DB exception is retried until the DB is back; no message reaches the DLT.
- **MSG-AC-06** The same message consumed twice produces exactly one business effect.
- **MSG-AC-07** p99 publish latency < 50 ms at 300 inserts/s on the CI Testcontainers setup.
- **MSG-AC-08** With two instances under load: freezing the leader (container pause) or blackholing its DB traffic
  lets the other instance publish within 5 s; after unfreezing, the old leader stops before its next batch, per-key
  order on the consumer is kept and only duplicates appear; a leader whose publish loop is blocked hands over within
  5 s and reports not live. (ADR-006 amendment)

## 4. `contracts`

- Java records for every Kafka message ([02](02-messaging-contracts.md)) and every **internal** REST request /
  response DTO (orchestrator, ledger, risk, vop internal APIs). The customer-facing API of payment-api and the
  scheme-facing VoP responder API are *not* in `contracts` — they are owned by their service.
- Records use `payment-domain` value objects with Jackson serialisers registered by `ContractsModule`
  (`Money` → `amountMinor` + `currency`, `Iban` → string, …).
- `ContractValidator.validate(Message)` checks required fields and value-object validity; consumers call it before
  handling (throws `ContractViolationException` → DLT).
  - `Message` is a sealed interface of records. Each record declares its type and schema version with
    `@Contract(type, version)`.
  - Every component is required unless it is marked `@OptionalField`; an optional field is not written when null.
  - Value objects are valid by construction, so an invalid one on the wire (bad IBAN, non-EUR currency) is reported
    when the message is read. `ContractJson.read(json, type)` turns that failure, a missing field or malformed JSON
    into a `ContractViolationException` carrying field paths and codes, never values. It then runs
    `ContractValidator`.
  - `ContractJson.write` validates before writing and rejects an `UNKNOWN` enum constant anywhere in the message:
    `UNKNOWN` is only the reader's fallback (evolution rules below). The per-type rules leave `UNKNOWN` to the
    consumer.
- **Golden samples:** `src/test/resources/golden/<Type>.v<n>.json` for every message type and version. Tests assert
  (a) the current record serialises to the sample, (b) every older sample still deserialises (backward
  compatibility), (c) a sample with an extra unknown field deserialises.
- **Evolution rules:** add optional fields only; never remove, rename or change the type of a field; never change
  the meaning of an enum constant; adding an enum constant requires all consumers to handle unknown constants
  (they map to an `UNKNOWN` fallback and DLT only if they cannot proceed). A breaking change is a new message type
  (`…V2`) published in parallel until all consumers migrated.
