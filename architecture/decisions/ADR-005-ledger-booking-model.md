# ADR-005 Ledger booking model: memo reservations and contention-free technical accounts

- **Status:** Accepted
- **Date:** 2026-10-21

## Context
- Every settled payment, outgoing or incoming, posts to the **CSM settlement account**. With optimistic locking on the account
  version that single row serialises all settlements, so throughput collapses into retries.
- Incoming payments registered a pending credit as "booked but not available" before settlement. Booking money that has not
  settled distorts booked balances and `camt.054`, and every CSM rejection after our `ACCP` needs a reversal entry.

## Decision
- **Holds and pending credits are memos**, not journal entries. A hold reduces the available balance of the payer; a pending credit
  changes neither booked nor available balance of the payee. Both are rows with an id (`holdId = paymentId`, pending credit id = `TxId`),
  an amount and a status (`ACTIVE`, `SETTLED`, `RELEASED` / `CANCELLED`).
- A journal entry is posted only at settlement: Dr payer / Cr CSM settlement account (outgoing), Dr CSM settlement account / Cr payee (incoming),
  Dr payer / Cr payee (on-us).
- Creating a pending credit checks the creditor account status (exists, open, not blocked, EUR) in the same transaction, so the check cannot be
  outdated by a block that lands between two calls. Settling an on-us hold checks the payee account the same way.
- **Two account kinds:**
  - *Customer accounts* keep a running balance with an optimistic-lock version and the available-balance check.
  - *Technical accounts* (CSM settlement, funding) are flagged `technical`: postings are append-only, there is no balance check and no version
    update on the account row. Their balance is derived from postings plus periodic snapshots.

## Options considered
| Option | Assessment |
|---|---|
| A. Optimistic locking on every account (status quo) | Simple, but the CSM settlement account becomes a single hot row. |
| B. Shard the CSM settlement account into N sub-accounts | Spreads contention, but needs aggregation for reporting and still locks. |
| **C. Append-only postings without balance check for technical accounts** | No contention; technical accounts may go negative, which is correct for them. |

## Consequences
- Settlements only lock the customer account involved; the hot-row bottleneck disappears.
- Booked balances and `camt.054` reflect settled money only; CSM rejections after `ACCP` need no reversal entries.
- Reading the balance of a technical account costs a sum over postings since the last snapshot; a snapshot job is needed.
- CSM liquidity (our position at TIPS / RT1) is still not modelled; reconciliation with `camt.053` (UC-12) will compare it with the derived balance.
