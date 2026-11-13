package com.tpe.paymentorchestrator.domain.payment;

/**
 * The state of a payment (specs/11-payment-orchestrator.md §6.1). Which states a payment passes through depends on
 * its direction; {@link PaymentStateMachine} has the allowed moves.
 */
public enum PaymentState {
    RECEIVED,
    /** Outgoing: risk allowed the payment. */
    RISK_CHECKED,
    /** Outgoing: the hold on the payer's account exists. */
    FUNDS_RESERVED,
    /** Outgoing: {@code SendCreditTransfer} is in the outbox. */
    SENT_TO_CSM,
    /** Incoming: {@code AcceptCreditTransfer} is in the outbox. */
    ACCEPT_REQUESTED,
    /** Incoming: the gateway sent the accepting answer to the CSM. */
    ACCEPTED,
    /** No outcome from the CSM in time; status requests are running (UC-05). */
    INVESTIGATING,
    SETTLED,
    REJECTED;

    /** {@code SETTLED} and {@code REJECTED}: never left; contradicting facts open a case. */
    public boolean isFinal() {
        return this == SETTLED || this == REJECTED;
    }
}
