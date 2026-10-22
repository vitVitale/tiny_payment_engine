# 16 notification-service

Architecture: [C4 components](../architecture/c4/03-components-notification-service.puml),
[UC-08](../architecture/use-cases/UC-08-notify-customer.md).

## 1. Responsibilities

- Consume `PaymentSettled` / `PaymentRejected`, resolve subscriptions by account, deliver signed webhooks (JSON or
  `camt.054`) with retries for up to 24 h.
- Subscription management for customer channels; failed-delivery inspection and redelivery for the back office.

Not responsible for: anything on the payment path — a notification failure never affects payment processing.
It keeps no copy of account-holder data; everything needed is in the event (ADR-002).

## 2. Module layout

| Package | C4 component |
|---|---|
| `api.events` | Payment events listener |
| `api.subscriptions` | Subscription API |
| `api.deliveries` | Delivery admin API |
| `application.resolve` | Subscription resolver |
| `application.build` | Notification builder |
| `application.dispatch` | Delivery dispatcher |
| `infrastructure.retry` | Retry scheduler |
| `infrastructure.webhook` | Webhook client |
| `infrastructure.persistence` | Repositories |

## 3. REST API

### 3.1 Subscription API (scope `notifications:manage`; IBAN must be in the token `accounts` claim)

| Method & path | Purpose |
|---|---|
| `POST /v1/subscriptions` | Register |
| `GET /v1/subscriptions?iban=` | List own subscriptions (secret never returned) |
| `DELETE /v1/subscriptions/{id}` | Deactivate |

```json
POST /v1/subscriptions
{ "iban": "DE…", "url": "https://channel.example/hooks/payments", "format": "JSON",
  "signingSecret": "base64:…", "events": ["SETTLED", "REJECTED"] }
201 { "subscriptionId": "…", "iban": "DE…", "url": "…", "format": "JSON", "events": [...], "createdAt": "…" }
```

- `format`: `JSON` or `CAMT054` (one format per subscription; register two subscriptions for both).
- `url` must be `https` (except in `local` / `test`), no private / loopback addresses in `prod` (SSRF guard).
- `signingSecret` ≥ 32 bytes; stored encrypted (AES-GCM, key from `tpe.notification.secret-key`).
- `events` filter; default both.

### 3.2 Delivery admin API (role `BACKOFFICE_OPERATOR`)

- `GET /v1/deliveries?status=FAILED&from=&to=` — list.
- `GET /v1/deliveries/{id}` — delivery with attempts (status code, latency, error), body not shown (personal data).
- `POST /v1/deliveries/{id}/redeliver` — new attempt series for a `FAILED` delivery.

### 3.3 Webhook request (to the channel)

```
POST <subscription url>
Content-Type: application/json            (or application/xml for CAMT054)
Idempotency-Key: <eventId>
TPE-Event-Type: payment.settled
TPE-Signature: t=1761037530,v1=5f2c…      (hex HMAC-SHA256(secret, t + "." + body))
```

JSON body:

```json
{
  "eventId": "…", "type": "payment.settled", "occurredAt": "…",
  "role": "DEBTOR",
  "payment": { "paymentId": "…", "direction": "OUTGOING", "status": "ACSC", "reasonCode": null,
               "endToEndId": "…", "amount": { "value": "123.45", "currency": "EUR" },
               "counterparty": { "name": "…", "iban": "…" }, "remittanceInformation": "…",
               "acceptedAt": "…", "finalisedAt": "…" }
}
```

`role` is `DEBTOR` or `CREDITOR` from the subscriber's point of view; `counterparty` is the other party.
`CAMT054` body: `camt.054.001.08` with one `Ntry` (`CdtDbtInd` per role, `Sts/Cd = BOOK`, `NtryRef =
bookingEntryId`, `AcctSvcrRef = paymentId`). Rejections are delivered as JSON only; `CAMT054` subscriptions receive
nothing for rejections (no booking happened).

Receivers must verify the signature, reject `t` older than 5 min, and deduplicate by `Idempotency-Key` (at-least-once).

## 4. Messaging

Consumes `payments.events` (group `notification-service`), dedup by `eventId`. Produces nothing.

## 5. Data model

```sql
CREATE TABLE subscriptions (
  subscription_id UUID PRIMARY KEY, iban VARCHAR(34) NOT NULL, client_id VARCHAR(100) NOT NULL,
  url VARCHAR(500) NOT NULL, format VARCHAR(8) NOT NULL CHECK (format IN ('JSON','CAMT054')),
  events JSONB NOT NULL, secret_ciphertext BYTEA NOT NULL, secret_key_id VARCHAR(20) NOT NULL,
  active BOOLEAN NOT NULL, created_at TIMESTAMPTZ NOT NULL, deactivated_at TIMESTAMPTZ
);
CREATE INDEX subscriptions_iban ON subscriptions (iban) WHERE active;

CREATE TABLE deliveries (
  delivery_id UUID PRIMARY KEY, event_id UUID NOT NULL, subscription_id UUID NOT NULL REFERENCES subscriptions,
  payment_id UUID NOT NULL, event_type VARCHAR(20) NOT NULL, role VARCHAR(8) NOT NULL,
  body TEXT NOT NULL, content_type VARCHAR(40) NOT NULL,
  status VARCHAR(10) NOT NULL CHECK (status IN ('PENDING','DELIVERED','FAILED','SKIPPED')),
  attempts INT NOT NULL DEFAULT 0, next_attempt_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL,
  delivered_at TIMESTAMPTZ, failed_at TIMESTAMPTZ,
  UNIQUE (event_id, subscription_id)
);
CREATE INDEX deliveries_due ON deliveries (next_attempt_at) WHERE status = 'PENDING';

CREATE TABLE delivery_attempts (
  delivery_id UUID NOT NULL REFERENCES deliveries, attempt INT NOT NULL, at TIMESTAMPTZ NOT NULL,
  http_status INT, latency_ms INT, error VARCHAR(500), PRIMARY KEY (delivery_id, attempt)
);

CREATE TABLE event_records (                 -- UC-08 2a: event acknowledged and recorded even without subscribers
  event_id UUID PRIMARY KEY, payment_id UUID NOT NULL, event_type VARCHAR(20) NOT NULL,
  deliveries_created INT NOT NULL, received_at TIMESTAMPTZ NOT NULL
);
```

Plus `inbox`. Delivery bodies are deleted (set to `''`) 30 days after the final status; rows kept 13 months.

## 6. Behaviour

1. Listener transaction: inbox → resolve active subscriptions for the event's account IBANs:
   - `OUTGOING`: debtor → role `DEBTOR`; on-us also creditor → role `CREDITOR`.
   - `INCOMING`: creditor → role `CREDITOR`; **`PaymentRejected` with `direction = INCOMING` is recorded but not
     delivered** (the payee never saw the payment).
2. Filter by `events` and format rules (§3.3); build the body per subscription; insert `deliveries` (`PENDING`,
   `next_attempt_at = now`) and `event_records`; commit. No HTTP inside the listener.
3. Dispatcher (lease-based worker, like [15 §6.5](15-csm-gateway.md#65-transmitter)) sends due deliveries:
   decrypt secret, sign, POST with connect 2 s / read 5 s.
4. `2xx` → `DELIVERED`. Otherwise attempt recorded, `next_attempt_at` from the schedule
   `10s, 30s, 1m, 5m, 15m, 30m, 1h, 1h, …` until `created_at + 24h` → `FAILED` (UC-08 5a). `410 Gone` →
   `FAILED` immediately and subscription deactivated.
5. Redelivery resets `status = PENDING`, `attempts = 0`, new 24 h window.

Ordering of webhooks is not guaranteed; receivers use `occurredAt` / status.

## 7. Configuration

| Property | Default |
|---|---|
| `tpe.notification.retry-schedule` | `10s,30s,1m,5m,15m,30m,1h` (last repeats) |
| `tpe.notification.retry-window` | `24h` |
| `tpe.notification.timeouts.connect` / `.read` | `2s` / `5s` |
| `tpe.notification.secret-key` | env var, required |
| `tpe.notification.dispatch.concurrency` | `64` |

## 8. Non-functional requirements

- Final event → first delivery attempt p99 ≤ 5 s; retries start ≤ 5 s after their scheduled time.
- Never on the payment path: an outage of this service must not change any payment-path service level (UC-08 NFR).
- Metrics: `tpe.notification.dispatch.delay{format}`, `tpe.notification.attempt.lateness{format}`,
  `tpe.notification.deliveries{outcome}`.
- Service levels: SLO-NTF-01 … -04 in [25 §5.7](25-service-levels.md#57-notification-service); SLA-05 rests on them.

## 9. Acceptance criteria

- **NTF-AC-01** `PaymentSettled` for a subscribed debtor → one signed JSON webhook with `Idempotency-Key = eventId`;
  signature verifies with the registered secret. (UC-08 1–5)
- **NTF-AC-02** On-us settlement → two deliveries (debtor, creditor) with the right `role`. (UC-08 2)
- **NTF-AC-03** `CAMT054` subscription → XSD-valid `camt.054.001.08` with the booking entry reference.
- **NTF-AC-04** Receiver returns `500` → retries per schedule; after 24 h `FAILED`, visible in the admin API. (UC-08 5a)
- **NTF-AC-05** Event without subscriptions → recorded in `event_records`, nothing sent. (UC-08 2a)
- **NTF-AC-06** The same event consumed twice → one delivery per subscription.
- **NTF-AC-07** notification-service down for 10 min → payments keep settling; on restart all missed events are
  delivered. (UC-08 NFR)
- **NTF-AC-08** Registering a subscription for an IBAN not in the token → `403`; `http://` URL in `prod` → `400`.
- **NTF-AC-09** `PaymentRejected` with `direction = INCOMING` for a subscribed creditor → recorded in `event_records`,
  no delivery created. (Q-19)
