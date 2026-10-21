# Use Cases

Diagram: [use-case-diagram.puml](use-case-diagram.puml)

## Actors

| Actor | Type | Description |
|---|---|---|
| **Payer** | Person | Customer of our bank who sends an instant credit transfer (through a customer channel). |
| **Payee** | Person | Customer of our bank who receives an instant credit transfer. |
| **Back-office operator** | Person | Bank employee who manages accounts and investigates exceptions. |
| **Compliance officer** | Person | Bank employee who reviews sanctions screening hits. |
| **CSM** | External system | TIPS / RT1 — clears and settles instant payments between PSPs (simulated). |
| **Counterparty PSP** | External system | Another bank in the scheme, reached through the CSM (payments) or the VoP scheme (name checks). Simulated. |
| **Sanctions list provider** | External system | Source of EU / UN sanctions lists. |
| **Scheduler** | Time | Triggers time-based use cases (deadlines, daily screening). |

## Catalogue

| ID | Use case | Primary actor | Containers | Scope |
|---|---|---|---|---|
| [UC-01](UC-01-initiate-instant-credit-transfer.md) | Initiate instant credit transfer | Payer | payment-api, vop-service, payment-orchestrator | MVP |
| [UC-02](UC-02-verify-payee-outgoing.md) | Verify payee before sending | Payer | payment-api, vop-service | MVP |
| [UC-03](UC-03-answer-vop-request.md) | Answer VoP request from another PSP | Counterparty PSP | vop-service, ledger-service | MVP |
| [UC-04](UC-04-execute-outgoing-payment.md) | Execute outgoing payment | Payment orchestrator (system) | payment-orchestrator, risk-service, ledger-service, csm-gateway, notification-service | MVP |
| [UC-05](UC-05-resolve-missing-csm-response.md) | Resolve missing CSM response | Scheduler | payment-orchestrator, csm-gateway, ledger-service | MVP |
| [UC-06](UC-06-receive-incoming-payment.md) | Receive incoming instant payment | CSM | csm-gateway, payment-orchestrator, risk-service, ledger-service, notification-service | MVP |
| [UC-07](UC-07-query-payment-status.md) | Query payment status | Payer | payment-api, payment-orchestrator | MVP |
| [UC-08](UC-08-notify-customer.md) | Notify customer of debit / credit | Payment orchestrator (system) | notification-service | MVP |
| [UC-09](UC-09-daily-sanctions-screening.md) | Daily sanctions screening of customers | Scheduler / Compliance officer | risk-service, ledger-service | MVP |
| [UC-10](UC-10-manage-accounts.md) | Manage accounts and balances | Back-office operator | ledger-service | MVP |
| UC-11 | Recall a payment (`camt.056` → `camt.029` / `pacs.004`) | Payer / Counterparty PSP | payment-orchestrator, csm-gateway, ledger-service | Later |
| UC-12 | Reconcile with CSM settlement (`camt.053`) | Back-office operator | csm-gateway, ledger-service | Later |

## Relationships

- UC-01 **includes** UC-04 (once accepted, the payment is executed asynchronously).
- UC-01 **is preceded by** UC-02 (the customer channel runs VoP before the payer authorises the payment).
- UC-04 **extends to** UC-05 when no `pacs.002` arrives before the 20 s deadline.
- UC-04 and UC-06 **include** UC-08.
- UC-02 on our side triggers UC-03 on the counterparty side (and vice versa).

## Specification template

Every use case file follows the same structure:

1. **Goal** — one sentence.
2. **Actors** — primary and supporting.
3. **Preconditions**
4. **Trigger**
5. **Main success scenario** — numbered steps.
6. **Alternative and exception flows** — referencing main steps, with ISO 20022 reason codes.
7. **Postconditions** — success and failure guarantees.
8. **ISO 20022 messages**
9. **Non-functional constraints** — timing, idempotency, consistency.
10. **Involved containers**
