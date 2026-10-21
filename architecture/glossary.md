# Glossary

## Scheme and roles

| Term | Meaning |
|---|---|
| **SEPA** | Single Euro Payments Area. |
| **SCT Inst** | SEPA Instant Credit Transfer scheme, defined by the EPC rulebook. Funds are made available to the payee within seconds, 24/7/365. |
| **EPC** | European Payments Council — owner of the SEPA scheme rulebooks. |
| **PSP** | Payment Service Provider (bank or payment institution). |
| **Originator PSP** | PSP of the payer; sends the instant credit transfer. |
| **Beneficiary PSP** | PSP of the payee; receives the instant credit transfer and credits the payee. |
| **CSM** | Clearing and Settlement Mechanism that routes and settles instant payments between PSPs, e.g. **TIPS** (Eurosystem) or **RT1** (EBA CLEARING). |
| **IPR** | Instant Payments Regulation, Regulation (EU) 2024/886. Mandates VoP, price parity and changes sanctions screening rules. |
| **VoP** | Verification of Payee — before the payer authorises a transfer, the Originator PSP checks with the Beneficiary PSP that the payee name matches the IBAN. Results: *match*, *close match*, *no match*, *not possible*. |
| **Target execution time** | 10 seconds from time stamp to confirmation to the Originator PSP. |
| **Time-out deadline** | 20 seconds — if the Originator PSP has no confirmation by then, the transaction must be treated as rejected / investigated. |
| **Send-by time** | Latest time at which csm-gateway may send an outgoing `pacs.008` (default acceptance + 10 s). After it, the message is never sent. |
| **Answer deadline** | Latest time at which csm-gateway answers an incoming `pacs.008` (default receipt + 5 s); without a verdict by then it sends `RJCT` `AB05`. |
| **R-transactions** | Exception flows: *reject* (before settlement), *recall* / *return* (after settlement). |

## Identifiers

| Term | Meaning |
|---|---|
| **IBAN** | International Bank Account Number. |
| **BIC** | Business Identifier Code of a PSP. |
| **MsgId** | Unique message identifier assigned by the sender of an ISO 20022 message. |
| **EndToEndId** | Identifier assigned by the payer, passed unchanged through the whole chain. |
| **TxId** | Transaction identifier assigned by the first instructing agent (Originator PSP). Unique only per Originator PSP, so a transaction is identified by (`DbtrAgt` BIC, `TxId`) — [ADR-010](decisions/ADR-010-incoming-transaction-identity.md). |
| **UETR** | Unique End-to-end Transaction Reference (UUID v4). |
| **Idempotency-Key** | HTTP header used by API clients to make request retries safe. |
| **commandId** | Id derived by payment-api from the API client id and the `Idempotency-Key`; payment-orchestrator deduplicates `SubmitPayment` on it. |
| **Pending action** | The next remote step of a payment saga, persisted on the payment row so any instance can resume it ([ADR-008](decisions/ADR-008-resumable-saga-steps.md)). |
| **vopReference** | Id of a VoP result; valid for a limited time and only for the debtor account, payee IBAN and payee name it was issued for. |

## ISO 20022 messages

| Message | Name | Direction / use |
|---|---|---|
| `pain.001` | CustomerCreditTransferInitiation | Customer → Originator PSP: initiate a transfer. |
| `pain.002` | CustomerPaymentStatusReport | Originator PSP → customer: status of the initiation. |
| `pacs.008` | FIToFICustomerCreditTransfer | Interbank instant credit transfer via the CSM. |
| `pacs.002` | FIToFIPaymentStatusReport | Positive (`ACCP` / `ACSC`) or negative (`RJCT`) confirmation. |
| `pacs.028` | FIToFIPaymentStatusRequest | Status investigation when no `pacs.002` was received in time. |
| `pacs.004` | PaymentReturn | Return of funds after a successful recall. |
| `camt.056` | FIToFIPaymentCancellationRequest | Recall request. |
| `camt.029` | ResolutionOfInvestigation | Negative answer to a recall. |
| `camt.054` | BankToCustomerDebitCreditNotification | Notification of a debit / credit to the account holder. |
| `camt.053` | BankToCustomerStatement | Statement, used for reconciliation with the CSM. |
| `head.001` | BusinessApplicationHeader (BAH) | Envelope header for interbank messages. |

## Status codes used in this design

| Code | Meaning |
|---|---|
| `ACCP` | Accepted customer profile — Beneficiary PSP accepted the payment (positive `pacs.002`). |
| `ACSC` | Accepted settlement completed — CSM confirms settlement. |
| `RJCT` | Rejected. |
| `PDNG` | Pending (internal / status report). |

## Reason codes (ExternalStatusReason1Code) used in this design

| Code | Meaning |
|---|---|
| `AB03` | Transaction aborted due to timeout (used when an outgoing `pacs.008` was not sent before its send-by time). |
| `AB05` | Timeout at the creditor agent. |
| `AB06` | Timeout at the instructed agent. |
| `AC01` | Incorrect account number. |
| `AC04` | Closed account number. |
| `AC06` | Blocked account. |
| `AG01` | Transaction forbidden on this account type. |
| `AM04` | Insufficient funds. |
| `FF01` | Invalid file format / schema validation failure. |
| `FR01` | Fraud suspected. |
| `MS03` | Reason not specified — used for internal technical failures (e.g. ledger unreachable before the send-by time). |
| `RR04` | Regulatory reason (e.g. sanctions). |

## Ledger terms

| Term | Meaning |
|---|---|
| **Double entry** | Every posting has equal debit and credit legs; the sum over a journal entry is zero. |
| **Memo** | A reservation recorded beside the journal (hold or pending credit); it is not a journal entry and never changes the booked balance. |
| **Hold** | Memo reserving funds on the payer account; reduces available balance, not booked balance. |
| **Release** | Cancels a hold (compensation when the payment is rejected). |
| **Settle** | Converts a hold into a journal entry: debit of the payer and credit of the CSM settlement account (or of the payee, on-us). |
| **Pending credit** | Memo for an incoming payment we accepted but that is not yet settled; changes neither booked nor available balance of the payee. Confirmed into a journal entry on `ACSC`, cancelled on rejection. |
| **Available balance** | Booked balance minus active holds. |
| **Customer account** | Account of a bank customer; keeps a running balance with optimistic locking and an available-balance check. |
| **Technical account** | Internal account (CSM settlement, funding); append-only postings, no balance check, no lock; balance derived from postings and snapshots. |
| **CSM settlement account** | Technical account mirroring our position at the CSM. |
| **Tombstone** | Final-state row recorded when a hold is released or a pending credit cancelled before it was created; a late create with the same id is refused ([ADR-009](decisions/ADR-009-ledger-tombstones.md)). |
| **Provisional block** | Account block applied automatically on a high-confidence sanctions hit, before the compliance officer's review. |
