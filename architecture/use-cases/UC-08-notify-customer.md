# UC-08 Notify customer of debit / credit

## Goal
Inform the payer (outgoing) or payee (incoming) about the final outcome of a payment.

## Actors
- **Primary:** payment-orchestrator (emits domain events).
- **Supporting:** notification-service, customer channel (webhook receiver).

## Preconditions
- The customer channel has registered a webhook endpoint and a signing secret for the customer.

## Trigger
`PaymentSettled` or `PaymentRejected` event on Kafka topic `payments.events`.

## Main success scenario
1. notification-service consumes the event (inbox dedup by event id).
2. notification-service resolves the subscriptions for the account ids carried in the event (payer account for outgoing, payee account for incoming; both for on-us). It keeps no copy of account-holder data.
3. notification-service builds the notification: JSON webhook body, plus `camt.054` for subscribers that requested ISO 20022.
4. notification-service signs the body (HMAC) and delivers it with an `Idempotency-Key` equal to the event id.
5. The receiver responds `2xx`; the delivery is marked `DELIVERED`.

## Alternative and exception flows
- **5a. Non-2xx or timeout:** retry with exponential back-off for up to 24 h, then mark `FAILED` and expose it to the back office.
- **2a. No subscription:** event is acknowledged and recorded; nothing is sent.

## Postconditions
- A delivery record exists for every event and subscription.

## ISO 20022 messages
`camt.054` (out), optionally `pain.002`.

## Non-functional constraints
- At-least-once delivery; receivers deduplicate by `Idempotency-Key`.
- Notification failures never affect payment processing.

## Involved containers
payment-orchestrator, notification-service.
