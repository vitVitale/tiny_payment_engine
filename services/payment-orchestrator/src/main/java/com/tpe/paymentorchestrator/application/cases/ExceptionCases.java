package com.tpe.paymentorchestrator.application.cases;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentorchestrator.domain.payment.CaseResolution;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCase;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code exception_cases} as an operator sees them (specs/11-payment-orchestrator.md §3.2), with what a case's
 * payment went through: its transitions and the CSM events received for it. Cases are opened by
 * {@code Payments#openCase}.
 */
public interface ExceptionCases {

    Optional<ExceptionCase> find(UUID caseId);

    /** The case, locked for the current transaction ({@code FOR UPDATE}): no other resolution overtakes this one. */
    Optional<ExceptionCase> lock(UUID caseId);

    /**
     * Cases oldest first (by {@code case_id}, a UUID v7).
     *
     * @param status {@code null} for any
     * @param type {@code null} for any
     * @param after the last case of the previous page; {@code null} for the first page
     * @param limit at most this many; the page says whether more follow
     */
    Page list(ExceptionCase.Status status, ExceptionCaseType type, UUID after, int limit);

    /** Marks the locked case {@code RESOLVED} by {@code operator}. */
    void resolve(UUID caseId, CaseResolution resolution, String operator, String comment, Instant at);

    /** The payment's {@code payment_transitions}, oldest first. */
    List<Transition> transitions(PaymentId paymentId);

    /** The {@code csm.inbound.events} the inbox took for the transaction, in the order received. */
    List<CsmEvent> csmEvents(Bic debtorAgentBic, TxId txId);

    /** @param more a page after this one has cases */
    record Page(List<ExceptionCase> cases, boolean more) {}

    /** A row of {@code payment_transitions}; {@code from} and {@code reasonCode} may be {@code null}. */
    record Transition(String from, String to, String reasonCode, String cause, String actor, Instant at) {}

    /**
     * An inbox entry of the transaction.
     *
     * @param outcome the dedup key's outcome (specs/02-messaging-contracts.md §4), e.g. {@code REJECTED-AC04}
     */
    record CsmEvent(String type, String outcome, UUID eventId, Instant receivedAt) {}
}
