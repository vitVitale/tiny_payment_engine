package com.tpe.paymentorchestrator.application.incoming;

import com.tpe.contracts.AnswerSent;
import com.tpe.contracts.CreditTransferReceived;
import com.tpe.contracts.DecidedBy;
import com.tpe.messaging.consumer.UnknownMessageTypeException;
import com.tpe.observability.context.Spans;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.UuidV7Generator;
import com.tpe.paymentorchestrator.application.payments.LockedPayment;
import com.tpe.paymentorchestrator.application.payments.Payments;
import com.tpe.paymentorchestrator.application.runner.SagaRunner;
import com.tpe.paymentorchestrator.application.runner.SagaTelemetry;
import com.tpe.paymentorchestrator.domain.payment.AnswerDecision;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentParty;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.RejectionCause;
import java.time.Instant;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The incoming events of {@code csm.inbound.events} up to the answer (specs/11-payment-orchestrator.md §6.5, UC-06 2,
 * 6, 7), in the listener transaction after the inbox let the event through:
 *
 * <ul>
 *   <li>{@code CreditTransferReceived} → a new incoming payment, {@code RECEIVED}, its risk assessed next, unless its
 *       transaction (direction, debtor agent, {@code TxId}, ADR-010) has one;
 *   <li>{@code AnswerSent} → the payment follows the answer sent ({@link AnswerDecision}).
 * </ul>
 *
 * The next action runs after the commit.
 */
public class IncomingCsmEvents {

    static final String RECEIVED = "CSM_RECEIVED";

    /** {@code csm.inbound.events}, as the DLT knows it. */
    private static final String EVENTS = "csm.inbound.events";

    private static final Logger LOG = LoggerFactory.getLogger(IncomingCsmEvents.class);

    private final Payments payments;
    private final SagaRunner runner;
    private final SagaTelemetry telemetry;
    private final UuidV7Generator paymentIds;
    private final Spans spans;

    public IncomingCsmEvents(Payments payments, SagaRunner runner, SagaTelemetry telemetry,
            UuidV7Generator paymentIds, Spans spans) {
        this.payments = payments;
        this.runner = runner;
        this.telemetry = telemetry;
        this.paymentIds = paymentIds;
        this.spans = spans;
    }

    /** Must run in the listener transaction. */
    public void received(CreditTransferReceived event, Instant now) {
        // the listener's span continues the gateway's trace: the payment's later work continues it (ADR-014)
        Payment payment = Payment.received(new PaymentId(paymentIds.next()), event.txId(), event.msgId(),
                event.uetr(), event.endToEndId(), event.amount(),
                new PaymentParty(event.debtor().name(), event.debtor().iban(), event.debtorAgentBic()),
                new PaymentParty(event.creditor().name(), event.creditor().iban(), event.creditorAgentBic()),
                event.remittanceInformation(), event.receivedAt(), event.answerDeadline(),
                spans.currentTraceparent().orElse(null));
        if (!payments.insertIfNewTransaction(payment, now)) {
            // a duplicate under a new dedup key: should not happen (§6.5)
            LOG.warn("CreditTransferReceived for txId {} from {}, which has a payment already: ignored",
                    event.txId().value(), event.debtorAgentBic().value());
            return;
        }
        payments.recordTransition(payment.paymentId(), null, payment.state(), null, RECEIVED, now);
        runner.submitAfterCommit(payment.paymentId(), false);
        // one line per state transition (specs/26-observability.md §4.3); no parties or amounts
        LOG.info("payment {} txId {} from {} -> {} ({})", payment.paymentId().value(), payment.txId().value(),
                event.debtorAgentBic().value(), payment.state(), RECEIVED);
    }

    /**
     * Must run in the listener transaction, which locked the payment.
     *
     * @throws UnknownMessageTypeException for a status this version does not know: not retried, it goes to the DLT
     */
    public void answered(LockedPayment locked, AnswerSent answer, Instant now) {
        Payment payment = locked.payment();
        AnswerDecision.Answer status = switch (answer.status()) {
            case ACCP -> AnswerDecision.Answer.ACCP;
            case RJCT -> AnswerDecision.Answer.RJCT;
            case UNKNOWN -> throw new UnknownMessageTypeException(EVENTS, "AnswerSent " + answer.status());
        };
        switch (AnswerDecision.decide(payment, status, answer.reasonCode(), now)) {
            case AnswerDecision.Moved moved -> apply(locked, moved, answer.decidedBy(), now);
            case AnswerDecision.NoOp noOp -> LOG.info("payment {} AnswerSent {}: {}", payment.paymentId().value(),
                    answer.status(), noOp.why());
            case AnswerDecision.Contradiction contradiction -> {
                LOG.warn("payment {} AnswerSent {} in {}: {}, CONTRADICTING_CSM_OUTCOME case opened",
                        payment.paymentId().value(), answer.status(), payment.state(), contradiction.why());
                payments.openCase(payment.paymentId(), ExceptionCaseType.CONTRADICTING_CSM_OUTCOME,
                        Map.of("event", "AnswerSent", "outcome", answer.status().name(), "state",
                                payment.state().name()), now);
            }
        }
    }

    private void apply(LockedPayment locked, AnswerDecision.Moved moved, DecidedBy decidedBy, Instant now) {
        Payment payment = locked.payment();
        Payment next = moved.next();
        // no final event yet: a rejection cancels the pending credit first
        payments.applyEvent(locked, next, now);
        payments.recordTransition(next.paymentId(), payment.state(), next.state(), next.reasonCode(), moved.cause(),
                now);
        // one line per state transition (specs/26-observability.md §4.3); no parties or amounts
        LOG.info("payment {} txId {} {} -> {} ({}){}", next.paymentId().value(), next.txId().value(),
                payment.state(), next.state(), moved.cause(),
                next.reasonCode() == null ? "" : " " + next.reasonCode().code());
        if (next.state() == PaymentState.REJECTED) {
            RejectionCause cause = rejectionCause(decidedBy, next);
            afterCommit(() -> telemetry.rejected(next, cause));
        }
        if (next.pendingAction() != null) {
            runner.submitAfterCommit(next.paymentId(), false);
        }
    }

    /**
     * What the rejection counts as (specs/25-service-levels.md §1): our verdict came too late, or a technical
     * {@code MS03}, is ours; any other verdict is a business refusal.
     */
    private static RejectionCause rejectionCause(DecidedBy decidedBy, Payment rejected) {
        return decidedBy == DecidedBy.DEADLINE || ReasonCode.MS03.equals(rejected.reasonCode())
                ? RejectionCause.TECHNICAL
                : RejectionCause.BUSINESS;
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
