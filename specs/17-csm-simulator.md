# 17 csm-simulator

Test-only container (A2). Simulates TIPS / RT1, counterparty PSPs (payments and VoP) and — proposed, see
[Q-05](40-open-questions.md) — the sanctions list provider. It is a first-class test tool: every alternative flow in
the use cases must be reproducible with it, deterministically.

## 1. Responsibilities

- **CSM:** accept `pacs.008` / `pacs.002` / `pacs.028` from csm-gateway; route outgoing payments to a simulated
  Beneficiary PSP; apply scheme timing; settle; send `pacs.002` back; deliver incoming `pacs.008` to our gateway and
  forward our answer; answer status investigations.
- **Counterparty PSPs:** accept / reject / time out per scenario; answer VoP requests; send VoP requests to our responder.
- **Sanctions provider:** serve list versions.
- **Control API** for tests: create incoming payments, set scenarios, inspect what was received, control the clock.

State is in memory; restart = clean slate. Single instance.

## 2. Module layout

`api.csm` (CSM transport), `api.vop`, `api.sanctions`, `api.control`; `domain.scheme` (settlement model, timing),
`domain.scenarios`; `infrastructure.gateway` (client to csm-gateway). Uses `iso20022-model` for all XML.

## 3. Interfaces

### 3.1 CSM transport

- `POST /csm/v1/messages` — from csm-gateway (envelope). Responds `202` and processes asynchronously.
  Deduplicates by `MsgId` (a retried `pacs.008` is processed once; the retry is journalled as a duplicate).
  An envelope that is not a valid CSM envelope (unparsable, or a part failing its XSD), or a `pacs.008` the simulator
  cannot read (e.g. not EUR), is `400 INVALID_ENVELOPE` with the failing part and its violation codes in `errors`,
  logged at `ERROR`, and neither journalled nor marked processed. csm-gateway validates before it sends, so this only
  happens on a bug: the simulator is a test oracle (SIM-AC-02).
- Sends to csm-gateway `POST {tpe.sim.gateway-url}/csm/v1/inbound` (UTF-8 `application/xml`; connect 200 ms, read
  1 s). A send the gateway does not take is logged, not repeated and not journalled (the CSM's own retries are out of
  scope; an investigation recovers the status). Its `pacs.002` goes from the CSM (`tpe.sim.own-bic`, `InstgAgt`) to
  the `DbtrAgt` of the `pacs.008` (`InstdAgt`), with `OrgnlTxRef/DbtrAgt`.
- Timing reads the simulator's own injected clock (component tests replace it with a `MutableClock`): each piece of
  work is due at an instant and runs as of that instant, whenever the timeline worker (every `tpe.sim.tick`) finds
  it. `REJECT` answers at once; `DELAY:<ms>` is `ACCEPT` with `<ms>` as the beneficiary delay; a beneficiary answer
  that would come after the time-out deadline is a time-out (`RJCT AB05` at the deadline, no settlement). A settled
  transfer is settled at the instant its `pacs.002 ACSC` is sent (the beneficiary's acceptance and the CSM's
  settlement are one step here).

### 3.2 Scheme behaviour

**Outgoing (our bank = originator):**

1. On `pacs.008`: if `now > AccptncDtTm + 20s` → `pacs.002 RJCT AB05` and no settlement (the CSM refuses messages
   past the time-out deadline; this is what makes UC-05 4c safe). Otherwise apply the scenario of
   the creditor (§3.5).
2. `ACCEPT` → settle (record debit of our CSM position) → `pacs.002 ACSC` to gateway after `beneficiaryDelay`.
3. `REJECT(reason)` → `pacs.002 RJCT reason`.
4. `TIMEOUT` → the beneficiary never answers; at `AccptncDtTm + 20s` the CSM sends `pacs.002 RJCT AB05`.
5. `SILENT` → the CSM settles but sends no `pacs.002`; a later `pacs.028` is answered `ACSC` (UC-05 happy path).
6. `LOST` → the CSM acknowledges the transport (`202`) and drops the message; a later `pacs.028` is answered
   `UNKNOWN_TX` (UC-05 4c). The message is still journalled as received: the transport took it.
7. `LATE_STATUS:<ms>` → settles as `ACCEPT`, but sends the `pacs.002 ACSC` only at `AccptncDtTm + <ms>` (at the
   settlement, if that comes later); a `pacs.028` before that is answered `ACSC` (late `pacs.002` during an
   investigation, UC-05).

Every settlement, in either direction, is appended to the **settlement log** (`txId`, `debtorAgentBic`, `direction`,
`amountMinor`, `settledAt`). It is the CSM's view of what moved, used by the ledger reconciliation invariant
(INV-10 in [20 §3](20-testing-strategy.md#3-global-invariants)).

**Incoming (our bank = beneficiary):** `POST /sim/v1/incoming-payments` builds a `pacs.008` (BAH; new `TxId` and a
seeded counterparty BIC as `DbtrAgt` unless the request gives `txId` / `debtorAgentBic`) and sends it to the gateway. The
seeded BIC is the bank of the debtor IBAN in the bank directory ([01 §1.4](01-shared-libraries.md#14-bank-directory));
`CdtrAgt` is our BIC from it. Waits for our `pacs.002` until `answerTimeout` (default 6 s) on its own clock, counted
from sending the `pacs.008`; an answer at or after that, or a second answer, is journalled and changes nothing:

- `ACCP` in time → settle → `pacs.002 ACSC` to the gateway (settlement confirmation), unless the scenario says
  `REJECT_AFTER_ACCP` (→ `RJCT AB05`, UC-06 8a) or `NO_CONFIRMATION` (UC-06 8b).
- `RJCT` → record; nothing else sent.
- No answer → record timeout; send nothing to us.

**Investigation:** `pacs.028` → `pacs.002` with the final status if known; for a `TxId` it never received →
`pacs.002 TxSts = RJCT`, `StsRsnInf/Rsn/Prtry = UNKNOWN_TX` (the convention csm-gateway relies on,
[15 §6.4](15-csm-gateway.md#64-correlation-inbound-pacs002)). A transaction is the debtor agent's BIC and `TxId`
(`OrgnlTxRef/DbtrAgt`, `OrgnlTxId`). The answer's `OrgnlMsgId` is the `pacs.028`'s, with `OrgnlMsgNmId`
`pacs.028.001.03`. A `pacs.028` about a transaction that has no final status yet is not answered (the requester asks
again). A `pacs.028` retried with the same `MsgId` is answered once. Scenario `INVESTIGATION_SILENT` ignores
`pacs.028`; it joins a payment scenario with `+` (`SILENT+INVESTIGATION_SILENT`, E2E-O-11), and alone the payment is
`ACCEPT`. It is read when the `pacs.028` arrives, so setting the creditor's scenario again without it (clearing it)
makes the next `pacs.028` answered. The creditor is known only from a `pacs.008` the CSM received, so a `LOST`
transaction is answered `UNKNOWN_TX` whatever the scenario.

### 3.3 VoP

- `POST /vop/v1/verifications` — responder for counterparty IBANs: looks up the IBAN in the counterparty account
  table (control API) and applies the same matching thresholds as vop-service (copy of the rule, simplified:
  case-insensitive exact → `MATCH`, Jaro-Winkler ≥ 0.85 → `CLOSE_MATCH`); unknown IBAN → `NOT_POSSIBLE`.
  Scenario `VOP_SLOW` delays 6 s.
- `POST /sim/v1/vop-requests` — make the simulator act as a requesting PSP against our responder (UC-03 tests).

### 3.4 Sanctions list provider

- `GET /sanctions/v1/lists/{listCode}/metadata` → `{ "listCode": "EU", "sourceVersion": "2026-10-21T02:00", "contentHash": "…" }`
- `GET /sanctions/v1/lists/{listCode}` → JSON list:

  ```json
  { "listCode": "EU", "sourceVersion": "…",
    "entries": [ { "entryId": "EU.1234.56", "type": "PERSON", "names": ["Ivan Petrov", "Иван Петров"],
                   "datesOfBirth": ["1970-01-01"], "countries": ["RU"], "identifiers": [] } ] }
  ```

- Control: `PUT /sim/v1/sanctions/{listCode}` replaces the list (new hash); `POST /sim/v1/sanctions/outage` toggles
  `503` responses (UC-09 1a).

### 3.5 Control API (`/sim/v1`, test use only)

| Endpoint | Purpose |
|---|---|
| `PUT /sim/v1/counterparty-accounts` | Seed counterparty IBANs with holder names and bank codes |
| `PUT /sim/v1/scenarios/{creditorIban}` | Scenario for payments to that IBAN: `ACCEPT`, `REJECT:<code>`, `TIMEOUT`, `SILENT`, `LOST`, `LATE_STATUS:<ms>`, `DELAY:<ms>`, `INVESTIGATION_SILENT` (alone or `<scenario>+INVESTIGATION_SILENT`), `VOP_SLOW` |
| `POST /sim/v1/incoming-payments` | `{ creditorIban, amount, debtorName, debtorIban, scenario, txId?, debtorAgentBic? }` → returns `txId` |
| `POST /sim/v1/incoming-payments/raw` | send an arbitrary (possibly invalid) XML to the gateway |
| `GET /sim/v1/received?txId=` | every message the simulator received / sent for a transaction, with timestamps |
| `GET /sim/v1/settlements?since=` | settlement log entries (§3.2) since an instant |
| `POST /sim/v1/outage` | `{ "mode": "DOWN" \| "RESET" \| "SLOW" \| "UP", "delayMs": … }` for the CSM transport: `DOWN` answers `503` and discards the message; `RESET` closes the connection without a response and discards the message; `SLOW` delays processing by `delayMs` |
| `POST /sim/v1/reset` | clear all state |

Default scenario (no explicit one): `ACCEPT` with `beneficiaryDelay` 300 ms. Amount-based shortcuts for manual
testing: cents `.91` → `REJECT:AC01`, `.92` → `REJECT:AC04`, `.93` → `TIMEOUT`, `.94` → `SILENT`.

Bodies (JSON; a request the simulator cannot act on is `400` with a problem detail; writes answer `204`):

- `PUT /sim/v1/counterparty-accounts`: `[ { "iban": "…", "holderName": "…" } ]`, replacing all accounts. The bank
  code is not a separate field: it is the one the IBAN carries.
- `PUT /sim/v1/scenarios/{creditorIban}`: `{ "scenario": "REJECT:AC04" }`; a reason code is four characters.
- `POST /sim/v1/incoming-payments`: `{ "creditorIban", "amount": "12.34", "debtorName", "debtorIban", "scenario",
  "txId"?, "debtorAgentBic"? }` (`scenario` defaults to `ACCEPT`); answers `200` `{ "txId", "gatewayStatus": 202 }`,
  or `502 GATEWAY_UNREACHABLE` if csm-gateway gave no answer. The `pacs.008` is journalled as `SENT` before it goes;
  one the gateway refuses (non-`2xx`) is forgotten; one it gave no answer for is kept (it may have taken it). `400` for a debtor IBAN of no bank in the directory without
  `debtorAgentBic`, and for a (`debtorAgentBic`, `txId`) already sent.
- `POST /sim/v1/incoming-payments/raw`: the XML as the body, sent as it is, without validation; answers `200`
  `{ "gatewayStatus": 202 }` with the status csm-gateway answered, or `502 GATEWAY_UNREACHABLE` if it gave none. A message the gateway took
  that is a CSM envelope naming a transaction is journalled as `SENT`.
- `GET /sim/v1/received?txId=`: `[ { "kind": "RECEIVED" | "SENT", "messageType": "pacs.008.001.08", "msgId",
  "at", "duplicate", "envelope" } ]`, oldest first.
- `GET /sim/v1/settlements?since=`: `[ { "txId", "debtorAgentBic", "direction": "OUTGOING" | "INCOMING",
  "amountMinor", "settledAt" } ]`, oldest first; `since` is inclusive and optional.

The simulator plays `ACCEPT`, `REJECT:<code>`, `TIMEOUT`, `DELAY:<ms>`, `SILENT`, `LOST`, `LATE_STATUS:<ms>` and
`INVESTIGATION_SILENT`, answers investigations of outgoing transfers, and plays incoming payments with `ACCEPT`.
`VOP_SLOW` and the incoming scenarios `REJECT_AFTER_ACCP` / `NO_CONFIRMATION` are refused with `400`, and
investigations of incoming transfers are not answered yet; they follow in later work.

## 4. Configuration

| Property | Default |
|---|---|
| `tpe.sim.gateway-url` | `http://csm-gateway:8085` |
| `tpe.sim.own-bic` (CSM BIC) | `TIPSXXXXXXX` |
| `tpe.sim.beneficiary-delay` | `300ms` |
| `tpe.sim.answer-timeout` | `6s` |
| `tpe.sim.timeout-deadline` | `20s` (the scheme time-out deadline used wherever this spec says "+ 20s") |
| `tpe.sim.tick` | `5ms` (how often due work runs; at most this much is added to a scheduled answer) |
| `tpe.bank-directory.location` | see [01 §1.4](01-shared-libraries.md#14-bank-directory) (our BIC, counterparty BICs) |

## 5. Acceptance criteria

- **SIM-AC-01** Every scenario of [20 §4](20-testing-strategy.md#4-end-to-end-scenario-catalogue) can be set up through
  the control API alone, without code changes.
- **SIM-AC-02** All XML the simulator produces validates against the XSDs; it rejects invalid XML from the gateway
  with a logged error (it is a test oracle: invalid messages from us must fail tests loudly).
- **SIM-AC-03** A `pacs.008` retried with the same `MsgId` is settled once.
- **SIM-AC-04** A `pacs.008` arriving after `AccptncDtTm + 20s` is never settled.
- **SIM-AC-05** `LATE_STATUS:<ms>` settles once and delivers exactly one `pacs.002 ACSC` at `AccptncDtTm + <ms>`
  (± 100 ms); a `pacs.028` received earlier is answered `ACSC`.
- **SIM-AC-06** The settlement log contains exactly the settled transactions, in both directions; payments rejected,
  timed out, `LOST` or arriving late have no entry.
- **SIM-AC-07** Incoming payments created with an explicit `txId` and `debtorAgentBic` carry them in `TxId` and
  `DbtrAgt`; two with the same `txId` and different BICs are handled as separate transactions.
- **SIM-AC-08** Outage modes `DOWN` and `RESET` produce a `503` and a closed connection respectively, and in both
  cases the message is neither recorded as received nor settled.
