# ADR-011 The send-by guard applies to every transmission attempt

- **Status:** Accepted
- **Date:** 2026-10-21
- **Amends:** [ADR-004](ADR-004-csm-gateway-owns-scheme-timing.md)
- **Resolves:** [Q-20](../../specs/40-open-questions.md#q-20-gateway-transmission-rule)

## Context
ADR-004 says csm-gateway journals the `pacs.008` as *sent* before transmitting it and never sends it after `sendBy`.
It checks `sendBy` once, when it consumes the command. Two gaps remain:

- **Retries.** If the first HTTP call to the CSM fails (connection reset, timeout), the gateway retries. A retry after
  `sendBy` would send money the orchestrator may already be investigating as "probably not sent".
- **Journalled but never attempted.** If the gateway crashes after committing the *sent* journal entry but before the
  first HTTP call, the entry says *sent* although no byte left. The investigation then has to wait for the 20 s
  deadline and a `pacs.028` "unknown" answer before the hold is released, although we could know immediately.

## Decision
- The `sendBy` check runs **before every transmission attempt**, including the first and every retry. After `sendBy`
  no attempt is made; the transmission status becomes `GAVE_UP`.
- The attempt counter is incremented in the claim transaction **before** the HTTP call. So `attempts = 0` proves that
  no request was started.
- A transfer that reaches `GAVE_UP` with `attempts = 0` is switched from *sent* to **not sent** (`AB03`) and
  `CreditTransferNotSent` is published in the same transaction. With `attempts ≥ 1` it stays *sent*: the CSM may have
  it, and UC-05 resolves it.
- Retries reuse the same `MsgId`, so a CSM that received an earlier attempt deduplicates the retry.

## Options considered
| Option | Assessment |
|---|---|
| A. Check `sendBy` only at consumption (ADR-004 as written) | Leaves a retry after `sendBy` possible. |
| B. No retries at all | One network blip rejects the payment, although there is time left. |
| **C. Check before every attempt; `attempts = 0` ⇒ not sent** | Closes the retry gap and shortens the crash case from ~20 s + investigation to the moment `sendBy` passes. |

## Consequences
- The ADR-004 invariant "a `pacs.008` is never sent after `sendBy`" holds for retries too.
- A gateway crash before transmission is resolved at `sendBy` instead of through an investigation.
- The claim must commit before the HTTP call starts. That costs one extra small transaction per attempt and is
  required anyway by the lease-based transmitter.

## Action items
1. [ ] Implement in the transmitter ([15 §6.1, §6.5](../../specs/15-csm-gateway.md#61-outgoing-sendcredittransfer)).
2. [ ] Tests GW-AC-03 and GW-AC-04; chaos test CH-02.
