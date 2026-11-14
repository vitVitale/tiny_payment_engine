package com.tpe.paymentorchestrator.domain.payment;

import com.tpe.paymentdomain.CommandId;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import java.time.Instant;
import java.util.Objects;
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
        String traceContext) {

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
                timings.deadline(acceptedAt), PendingAction.ASSESS_RISK, null, null, null, null, traceContext);
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
                bookingEntryId, traceContext);
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
     * {@code REJECTED} with {@code reason}, finalised at {@code at}.
     *
     * @param compensation the action that undoes what the payment already holds ({@code RELEASE_HOLD},
     *     {@code CANCEL_CREDIT}); {@code null} if nothing needs undoing
     */
    public Payment rejected(StatusReason reason, PendingAction compensation, Instant at) {
        return moveTo(PaymentState.REJECTED, Objects.requireNonNull(reason, "reason"), compensation,
                Objects.requireNonNull(at, "at"));
    }

    private Payment moveTo(PaymentState to, StatusReason reason, PendingAction next, Instant finalised) {
        PaymentStateMachine.require(direction, state, to);
        return new Payment(paymentId, direction, to, reason, onUs, commandId, clientId, requestHash, txId, uetr,
                endToEndId, amount, debtor, creditor, remittance, vopReference, vopResult, acceptedAt, sendBy,
                deadlineAt, next, finalised, debtorAccountId, creditorAccountId, bookingEntryId, traceContext);
    }

    private Payment withAccounts(UUID debtorAccount, UUID creditorAccount, UUID entry) {
        return new Payment(paymentId, direction, state, reasonCode, onUs, commandId, clientId, requestHash, txId,
                uetr, endToEndId, amount, debtor, creditor, remittance, vopReference, vopResult, acceptedAt, sendBy,
                deadlineAt, pendingAction, finalisedAt, debtorAccount, creditorAccount, entry, traceContext);
    }

    /** Leaves out the parties and the remittance text, which are personal data (specs/26-observability.md §4.4). */
    @Override
    public String toString() {
        return "Payment[paymentId=" + paymentId.value() + ", direction=" + direction + ", state=" + state + ", txId="
                + txId.value() + ", amount=" + amount + "]";
    }
}
