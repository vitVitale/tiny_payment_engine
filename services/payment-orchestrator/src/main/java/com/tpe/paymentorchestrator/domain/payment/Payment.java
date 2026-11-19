package com.tpe.paymentorchestrator.domain.payment;

import com.tpe.paymentdomain.CommandId;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * A payment as the orchestrator records it (specs/11-payment-orchestrator.md §5). The outgoing-only fields
 * ({@code commandId}, {@code clientId}, {@code requestHash}, {@code vopReference}, {@code vopResult}, {@code sendBy},
 * {@code deadlineAt}) are {@code null} for an incoming payment; {@code reasonCode}, {@code remittance},
 * {@code pendingAction}, {@code finalisedAt}, the ledger ids and {@code traceContext} may be {@code null}.
 *
 * @param requestHash hex SHA-256 of the submit request, which tells a retry from another command with the same id
 * @param debtorAccountId the payer's ledger account, known once the hold exists
 * @param creditorAccountId the payee's ledger account: incoming and on-us payments, once credited
 * @param bookingEntryId the ledger journal entry that settled the payment
 * @param traceContext the W3C {@code traceparent} the payment's later work continues (ADR-014); {@code null}: a new
 *     root
 * @param incoming what an incoming payment adds: its {@code pacs.008}, answer deadline and verdict; {@code null} for an
 *     outgoing one
 */
public record Payment(
        PaymentId paymentId,
        PaymentDirection direction,
        PaymentState state,
        StatusReason reasonCode,
        boolean onUs,
        CommandId commandId,
        String clientId,
        String requestHash,
        TxId txId,
        Uetr uetr,
        EndToEndId endToEndId,
        Money amount,
        PaymentParty debtor,
        PaymentParty creditor,
        RemittanceInformation remittance,
        String vopReference,
        PayeeVerification vopResult,
        Instant acceptedAt,
        Instant sendBy,
        Instant deadlineAt,
        PendingAction pendingAction,
        Instant finalisedAt,
        UUID debtorAccountId,
        UUID creditorAccountId,
        UUID bookingEntryId,
        String traceContext,
        Incoming incoming) {

    public Payment {
        Objects.requireNonNull(paymentId, "paymentId");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(txId, "txId");
        Objects.requireNonNull(uetr, "uetr");
        Objects.requireNonNull(endToEndId, "endToEndId");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(debtor, "debtor");
        Objects.requireNonNull(creditor, "creditor");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        if ((direction == PaymentDirection.INCOMING) != (incoming != null)) {
            throw new IllegalArgumentException("an incoming payment, and only one, has its incoming terms");
        }
    }

    /**
     * A submitted outgoing payment (§3.1): {@code RECEIVED}, its {@code send_by} and deadline from the timings, risk
     * assessment as the first action.
     */
    public static Payment submitted(PaymentId paymentId, OutgoingCommand command, String requestHash, TxId txId,
            Uetr uetr, PaymentParty debtor, PaymentParty creditor, boolean onUs, Instant acceptedAt,
            OutgoingTimings timings, String traceContext) {
        return new Payment(paymentId, PaymentDirection.OUTGOING, PaymentState.RECEIVED, null, onUs,
                command.commandId(), command.clientId(), Objects.requireNonNull(requestHash, "requestHash"), txId,
                uetr, command.endToEndId(), command.amount(), debtor, creditor, command.remittance(),
                command.vopReference(), command.vopResult(), acceptedAt, timings.sendBy(acceptedAt),
                timings.deadline(acceptedAt), PendingAction.ASSESS_RISK, null, null, null, null, traceContext,
                null);
    }

    /** Risk allowed the outgoing payment (§6.2 {@code ASSESS_RISK}): {@code RISK_CHECKED}, next {@code CREATE_HOLD}. */
    public Payment riskChecked() {
        return moveTo(PaymentState.RISK_CHECKED, null, PendingAction.CREATE_HOLD, null);
    }

    /**
     * The hold on the payer's account exists (§6.2 {@code CREATE_HOLD}): {@code FUNDS_RESERVED}; next the payee's
     * risk if on-us, else {@code SEND}.
     */
    public Payment fundsReserved(UUID debtorAccountId) {
        return moveTo(PaymentState.FUNDS_RESERVED, null, onUs ? PendingAction.ASSESS_PAYEE_RISK : PendingAction.SEND,
                null).withAccounts(Objects.requireNonNull(debtorAccountId, "debtorAccountId"), null, null);
    }

    /**
     * Risk allowed the payee of an on-us payment (§6.2 {@code ASSESS_PAYEE_RISK}): the state stays
     * {@code FUNDS_RESERVED}, next {@code SETTLE_ON_US}.
     *
     * @throws IllegalStateException unless this is an on-us payment holding the payer's funds
     */
    public Payment payeeRiskChecked() {
        if (!onUs || state != PaymentState.FUNDS_RESERVED) {
            throw new IllegalStateException("only a held on-us payment has its payee assessed, not " + this);
        }
        return new Payment(paymentId, direction, state, reasonCode, onUs, commandId, clientId, requestHash, txId,
                uetr, endToEndId, amount, debtor, creditor, remittance, vopReference, vopResult, acceptedAt, sendBy,
                deadlineAt, PendingAction.SETTLE_ON_US, finalisedAt, debtorAccountId, creditorAccountId,
                bookingEntryId, traceContext, incoming);
    }

    /**
     * The ledger moved the money from payer to payee (§6.2 {@code SETTLE_ON_US}): {@code SETTLED} at {@code at},
     * nothing left to do.
     */
    public Payment settledOnUs(UUID bookingEntryId, UUID creditorAccountId, Instant at) {
        return moveTo(PaymentState.SETTLED, null, null, Objects.requireNonNull(at, "at")).withAccounts(
                debtorAccountId, Objects.requireNonNull(creditorAccountId, "creditorAccountId"),
                Objects.requireNonNull(bookingEntryId, "bookingEntryId"));
    }

    /**
     * {@code SendCreditTransfer} is in the outbox (§6.2 {@code SEND}): {@code SENT_TO_CSM}, nothing to do until the
     * CSM's outcome arrives.
     *
     * @throws IllegalStateException unless this is a held payment to another bank
     */
    public Payment sent() {
        if (onUs || state != PaymentState.FUNDS_RESERVED) {
            throw new IllegalStateException("only a held payment to another bank is sent, not " + this);
        }
        return moveTo(PaymentState.SENT_TO_CSM, null, null, null);
    }

    /**
     * The CSM settled the payment (§6.5): the state stays until the ledger books it; next {@code SETTLE_HOLD} for an
     * outgoing payment, {@code CONFIRM_CREDIT} for an incoming one (UC-06 9).
     *
     * @throws IllegalStateException unless an outgoing payment is with the CSM, or an incoming one was answered
     *     accepting
     */
    public Payment csmSettled() {
        if (direction == PaymentDirection.INCOMING) {
            if (!isAcceptedIncoming()) {
                throw new IllegalStateException("only an accepted incoming payment is settled by the CSM, not " + this);
            }
            return withAction(PendingAction.CONFIRM_CREDIT);
        }
        if (!state.isWithCsm()) {
            throw new IllegalStateException("only a payment with the CSM is settled by it, not " + this);
        }
        return withAction(PendingAction.SETTLE_HOLD);
    }

    /**
     * An incoming payment the gateway accepted, or asked to accept: {@code ACCEPT_REQUESTED}, {@code ACCEPTED} or
     * {@code INVESTIGATING}. The CSM decides its outcome.
     */
    public boolean isAcceptedIncoming() {
        return direction == PaymentDirection.INCOMING && (state == PaymentState.ACCEPT_REQUESTED
                || state == PaymentState.ACCEPTED || state == PaymentState.INVESTIGATING);
    }

    /**
     * The ledger booked the pending credit to the payee (§6.2 {@code CONFIRM_CREDIT}): {@code SETTLED} at {@code at},
     * nothing left to do.
     *
     * @throws IllegalStateException unless the payment waits for its credit to be confirmed
     */
    public Payment settledFromCredit(UUID bookingEntryId, UUID creditorAccountId, Instant at) {
        requireConfirmingCredit();
        return moveTo(PaymentState.SETTLED, null, null, Objects.requireNonNull(at, "at")).withAccounts(
                debtorAccountId, Objects.requireNonNull(creditorAccountId, "creditorAccountId"),
                Objects.requireNonNull(bookingEntryId, "bookingEntryId"));
    }

    /**
     * The pending credit the CSM's settlement was to confirm is cancelled (§6.2 {@code CONFIRM_CREDIT},
     * {@code PENDING_CREDIT_CANCELLED}): the money and the record disagree, so the payment stays as it is, with
     * nothing left to do, for the exception case.
     *
     * @throws IllegalStateException unless the payment waits for its credit to be confirmed
     */
    public Payment confirmAbandoned() {
        requireConfirmingCredit();
        return withAction(null);
    }

    private void requireConfirmingCredit() {
        if (!isAcceptedIncoming() || pendingAction != PendingAction.CONFIRM_CREDIT) {
            throw new IllegalStateException("no credit to confirm for " + this);
        }
    }

    /**
     * The ledger settled the hold to the CSM (§6.2 {@code SETTLE_HOLD}): {@code SETTLED} at {@code at}, nothing left
     * to do.
     *
     * @throws IllegalStateException unless the payment waits for its hold to be settled
     */
    public Payment settledFromHold(UUID bookingEntryId, Instant at) {
        requireSettlingHold();
        return moveTo(PaymentState.SETTLED, null, null, Objects.requireNonNull(at, "at")).withAccounts(
                debtorAccountId, creditorAccountId, Objects.requireNonNull(bookingEntryId, "bookingEntryId"));
    }

    /**
     * The hold the CSM's settlement was to take is released (§6.2 {@code SETTLE_HOLD}, {@code HOLD_RELEASED}): the
     * money and the record disagree, so the payment stays as it is, with nothing left to do, for the exception case.
     *
     * @throws IllegalStateException unless the payment waits for its hold to be settled
     */
    public Payment settleAbandoned() {
        requireSettlingHold();
        return withAction(null);
    }

    private void requireSettlingHold() {
        if (!state.isWithCsm() || pendingAction != PendingAction.SETTLE_HOLD) {
            throw new IllegalStateException("no hold to settle for " + this);
        }
    }

    /**
     * A received incoming credit transfer (§6.5, UC-06 2): {@code RECEIVED} at the gateway's receipt, answered by
     * {@code answerDeadline}, risk assessment as the first action.
     */
    public static Payment received(PaymentId paymentId, TxId txId, MsgId msgId, Uetr uetr, EndToEndId endToEndId,
            Money amount, PaymentParty debtor, PaymentParty creditor, RemittanceInformation remittance,
            Instant receivedAt, Instant answerDeadline, String traceContext) {
        return new Payment(paymentId, PaymentDirection.INCOMING, PaymentState.RECEIVED, null, false, null, null, null,
                txId, uetr, endToEndId, amount, debtor, creditor, remittance, null, null, receivedAt, null, null,
                PendingAction.ASSESS_RISK, null, null, null, null, traceContext,
                new Incoming(msgId, answerDeadline, null, null));
    }

    /**
     * Risk allowed the incoming payment (§6.2 {@code ASSESS_RISK}, incoming): the state stays {@code RECEIVED}, next
     * {@code CREATE_PENDING_CREDIT}.
     *
     * @throws IllegalStateException unless this is a received incoming payment without a verdict
     */
    public Payment incomingRiskChecked() {
        requireAwaitingVerdict();
        return withAction(PendingAction.CREATE_PENDING_CREDIT);
    }

    /**
     * The pending credit on the payee's account exists and {@code AcceptCreditTransfer} goes out (§6.2
     * {@code CREATE_PENDING_CREDIT}): {@code ACCEPT_REQUESTED} with the verdict {@code ACCEPT}, nothing to do until the
     * gateway's answer.
     *
     * @throws IllegalStateException unless this is a received incoming payment without a verdict
     */
    public Payment acceptRequested(UUID creditorAccountId) {
        requireAwaitingVerdict();
        return withIncoming(incoming.withVerdict(Verdict.ACCEPT, null))
                .moveTo(PaymentState.ACCEPT_REQUESTED, null, null, null)
                .withAccounts(debtorAccountId, Objects.requireNonNull(creditorAccountId, "creditorAccountId"), null);
    }

    /**
     * The verdict {@code REJECT} with {@code reason}, and {@code RejectCreditTransfer} goes out (§6.2): the state stays
     * {@code RECEIVED} until the gateway's answer (ADR-003), nothing to do meanwhile.
     *
     * @throws IllegalStateException unless this is a received incoming payment without a verdict
     */
    public Payment verdictRejected(StatusReason reason) {
        requireAwaitingVerdict();
        return withIncoming(incoming.withVerdict(Verdict.REJECT, Objects.requireNonNull(reason, "reason")))
                .withAction(null);
    }

    /**
     * The gateway sent the accepting answer (§6.5 {@code AnswerSent(ACCP)}): {@code ACCEPTED}, keeping a
     * {@code CONFIRM_CREDIT} the CSM's settlement set when it overtook the answer.
     */
    Payment answerAccepted() {
        return moveTo(PaymentState.ACCEPTED, null, pendingAction, null);
    }

    private void requireAwaitingVerdict() {
        if (direction != PaymentDirection.INCOMING || state != PaymentState.RECEIVED || incoming.verdict() != null) {
            throw new IllegalStateException("only a received incoming payment without verdict gets one, not " + this);
        }
    }

    /**
     * {@code REJECTED} with {@code reason}, finalised at {@code at}.
     *
     * @param compensation the action that undoes what the payment already holds ({@code RELEASE_HOLD},
     *     {@code CANCEL_CREDIT}); {@code null} if nothing needs undoing
     */
    public Payment rejected(StatusReason reason, PendingAction compensation, Instant at) {
        return moveTo(PaymentState.REJECTED, Objects.requireNonNull(reason, "reason"), compensation,
                Objects.requireNonNull(at, "at"));
    }

    /**
     * The {@code OUTGOING_DEADLINE} timer fired at {@code at} (§6.4): before the payment reaches the CSM, the scheme
     * deadline rejects it {@code AB03}, releasing the hold it may have ({@code RISK_CHECKED}, {@code FUNDS_RESERVED});
     * on {@code RECEIVED} nothing exists to undo. An on-us payment holding its funds has no scheme deadline: its
     * actions retry to a definitive answer. A payment the CSM has without an answer goes into investigation
     * (UC-05 1), keeping its hold: the CSM may have settled it.
     *
     * @return empty when the deadline changes nothing: final, on-us and held, or investigating already
     */
    public Optional<Payment> atDeadline(Instant at) {
        return switch (state) {
            case RECEIVED -> Optional.of(rejected(ReasonCode.AB03, null, at));
            case RISK_CHECKED -> Optional.of(rejected(ReasonCode.AB03, PendingAction.RELEASE_HOLD, at));
            case FUNDS_RESERVED -> onUs ? Optional.empty()
                    : Optional.of(rejected(ReasonCode.AB03, PendingAction.RELEASE_HOLD, at));
            case SENT_TO_CSM -> Optional.of(moveTo(PaymentState.INVESTIGATING, null, null, null));
            default -> Optional.empty();
        };
    }

    /**
     * The compensation of a rejected payment is done (§6.2 {@code RELEASE_HOLD}): still {@code REJECTED}, nothing left
     * to do, so its final event follows.
     *
     * @throws IllegalStateException unless the payment is rejected and waits for its compensation
     */
    public Payment compensated() {
        if (state != PaymentState.REJECTED || pendingAction == null) {
            throw new IllegalStateException("nothing to compensate for " + this);
        }
        return new Payment(paymentId, direction, state, reasonCode, onUs, commandId, clientId, requestHash, txId,
                uetr, endToEndId, amount, debtor, creditor, remittance, vopReference, vopResult, acceptedAt, sendBy,
                deadlineAt, null, finalisedAt, debtorAccountId, creditorAccountId, bookingEntryId, traceContext, incoming);
    }

    private Payment moveTo(PaymentState to, StatusReason reason, PendingAction next, Instant finalised) {
        PaymentStateMachine.require(direction, state, to);
        return new Payment(paymentId, direction, to, reason, onUs, commandId, clientId, requestHash, txId, uetr,
                endToEndId, amount, debtor, creditor, remittance, vopReference, vopResult, acceptedAt, sendBy,
                deadlineAt, next, finalised, debtorAccountId, creditorAccountId, bookingEntryId, traceContext, incoming);
    }

    private Payment withAction(PendingAction next) {
        return new Payment(paymentId, direction, state, reasonCode, onUs, commandId, clientId, requestHash, txId,
                uetr, endToEndId, amount, debtor, creditor, remittance, vopReference, vopResult, acceptedAt, sendBy,
                deadlineAt, next, finalisedAt, debtorAccountId, creditorAccountId, bookingEntryId, traceContext, incoming);
    }

    private Payment withIncoming(Incoming terms) {
        return new Payment(paymentId, direction, state, reasonCode, onUs, commandId, clientId, requestHash, txId,
                uetr, endToEndId, amount, debtor, creditor, remittance, vopReference, vopResult, acceptedAt, sendBy,
                deadlineAt, pendingAction, finalisedAt, debtorAccountId, creditorAccountId, bookingEntryId,
                traceContext, terms);
    }

    private Payment withAccounts(UUID debtorAccount, UUID creditorAccount, UUID entry) {
        return new Payment(paymentId, direction, state, reasonCode, onUs, commandId, clientId, requestHash, txId,
                uetr, endToEndId, amount, debtor, creditor, remittance, vopReference, vopResult, acceptedAt, sendBy,
                deadlineAt, pendingAction, finalisedAt, debtorAccount, creditorAccount, entry, traceContext, incoming);
    }

    /** Leaves out the parties and the remittance text, which are personal data (specs/26-observability.md §4.4). */
    @Override
    public String toString() {
        return "Payment[paymentId=" + paymentId.value() + ", direction=" + direction + ", state=" + state + ", txId="
                + txId.value() + ", amount=" + amount + "]";
    }
}
