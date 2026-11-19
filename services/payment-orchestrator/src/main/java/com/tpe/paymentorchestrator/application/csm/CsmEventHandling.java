package com.tpe.paymentorchestrator.application.csm;

import com.tpe.contracts.AnswerSent;
import com.tpe.contracts.CreditTransferNotSent;
import com.tpe.contracts.CreditTransferReceived;
import com.tpe.contracts.CreditTransferRejected;
import com.tpe.contracts.CreditTransferSettled;
import com.tpe.contracts.Direction;
import com.tpe.contracts.Message;
import com.tpe.messaging.inbox.InboxGuard;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentorchestrator.application.incoming.IncomingCsmEvents;
import com.tpe.paymentorchestrator.application.payments.FinalEvents;
import com.tpe.paymentorchestrator.application.payments.LockedPayment;
import com.tpe.paymentorchestrator.application.payments.Payments;
import com.tpe.paymentorchestrator.application.runner.SagaRunner;
import com.tpe.paymentorchestrator.application.runner.SagaTelemetry;
import com.tpe.paymentorchestrator.domain.payment.CsmEventDecision;
import com.tpe.paymentorchestrator.domain.payment.CsmEventDecision.Apply;
import com.tpe.paymentorchestrator.domain.payment.CsmEventDecision.Contradiction;
import com.tpe.paymentorchestrator.domain.payment.CsmEventDecision.NoOp;
import com.tpe.paymentorchestrator.domain.payment.CsmOutcome;
import com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType;
import com.tpe.paymentorchestrator.domain.payment.CsmReport;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentDirection;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.RejectionCause;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The events of {@code csm.inbound.events} (specs/11-payment-orchestrator.md §6.5), in the listener transaction:
 * inbox (key {@code <debtorAgentBic>:<txId>:<type>:<outcome>}, 02 §4) → lock the payment by its transaction → apply.
 * An outgoing outcome is applied by {@link CsmEventDecision}: recording it cancels the payment's timers and sets the
 * next action, run after the commit; a contradiction opens a {@code CONTRADICTING_CSM_OUTCOME} case and changes
 * nothing else; the same holds for an incoming payment's settlement or rejection (UC-06 8a, 9).
 * {@code CreditTransferReceived} and {@code AnswerSent} go to {@link IncomingCsmEvents}. An event for no known payment
 * opens a case without a payment and is acknowledged.
 */
public class CsmEventHandling {

    /** The topic, also the inbox's {@code topic}. */
    public static final String EVENTS = "csm.inbound.events";

    private static final Logger LOG = LoggerFactory.getLogger(CsmEventHandling.class);

    private final Payments payments;
    private final InboxGuard inbox;
    private final FinalEvents finalEvents;
    private final IncomingCsmEvents incoming;
    private final SagaRunner runner;
    private final SagaTelemetry telemetry;
    private final Clock clock;

    public CsmEventHandling(Payments payments, InboxGuard inbox, FinalEvents finalEvents, IncomingCsmEvents incoming,
            SagaRunner runner, SagaTelemetry telemetry, Clock clock) {
        this.payments = payments;
        this.inbox = inbox;
        this.finalEvents = finalEvents;
        this.incoming = incoming;
        this.runner = runner;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    /**
     * @param event {@code CreditTransferReceived}, {@code AnswerSent}, {@code CreditTransferSettled},
     *     {@code CreditTransferRejected} or {@code CreditTransferNotSent}
     */
    @Transactional
    public void handle(UUID eventId, Message event) {
        Received received = Received.of(event);
        String dedupKey = received.debtorAgentBic().value() + ":" + received.txId().value() + ":" + received.type()
                + ":" + received.outcome();
        if (!inbox.firstTime(dedupKey, EVENTS, eventId)) {
            return;
        }
        Instant now = clock.instant();
        if (event instanceof CreditTransferReceived transfer) {
            incoming.received(transfer, now);
            return;
        }
        LockedPayment locked = payments.lockByTransaction(PaymentDirection.valueOf(received.direction().name()),
                received.debtorAgentBic(), received.txId()).orElse(null);
        if (locked == null) {
            LOG.warn("{} for txId {}, which is no payment of ours: case opened", received.type(),
                    received.txId().value());
            payments.openCase(null, ExceptionCaseType.DLT_MESSAGE, Map.of("cause", "UNKNOWN_TX", "topic", EVENTS,
                    "type", received.type(), "txId", received.txId().value(),
                    "debtorAgentBic", received.debtorAgentBic().value()), now);
            return;
        }
        if (event instanceof AnswerSent answer) {
            incoming.answered(locked, answer, now);
            return;
        }
        Payment payment = locked.payment();
        switch (CsmEventDecision.decide(payment, locked.csmOutcomeRecorded(), received.event(), now)) {
            case Apply apply -> apply(locked, apply, apply.cause(), Payments.SYSTEM, false, now);
            case NoOp noOp -> LOG.info("payment {} {}: {}", payment.paymentId().value(), received.type(),
                    noOp.why());
            case Contradiction contradiction -> {
                LOG.warn("payment {} {} in {}: {}, CONTRADICTING_CSM_OUTCOME case opened",
                        payment.paymentId().value(), received.type(), payment.state(), contradiction.why());
                payments.openCase(payment.paymentId(), ExceptionCaseType.CONTRADICTING_CSM_OUTCOME,
                        Map.of("event", received.type(), "outcome", received.outcome(), "state",
                                payment.state().name()), now);
            }
        }
    }

    /**
     * An outcome an operator confirmed for a payment in investigation ({@code CONFIRMED_SETTLED},
     * {@code CONFIRMED_REJECTED}, specs/11-payment-orchestrator.md §3.2): applied as the CSM's event would be, in the
     * caller's transaction, which locked the payment; the transition is recorded as the operator's even where the
     * state stays.
     *
     * @throws IllegalStateException if the event would not move the payment on
     */
    public void applyConfirmed(LockedPayment locked, CsmReport event, String cause, String operator,
            Instant now) {
        if (!(CsmEventDecision.decide(locked.payment(), locked.csmOutcomeRecorded(), event, now)
                instanceof Apply apply)) {
            throw new IllegalStateException(event + " does not apply to " + locked.payment());
        }
        apply(locked, apply, cause, operator, true, now);
    }

    /** @param recordUnchanged a transition row also when the state stays (an operator's decision is recorded) */
    private void apply(LockedPayment locked, Apply apply, String cause, String actor, boolean recordUnchanged,
            Instant now) {
        Payment payment = locked.payment();
        Payment next = apply.next();
        boolean finalEvent = next.state().isFinal() && next.pendingAction() == null;
        payments.applyCsmOutcome(locked, next, apply.outcome(), finalEvent, now);
        if (next.state() != payment.state() || recordUnchanged) {
            payments.recordTransition(next.paymentId(), payment.state(), next.state(), next.reasonCode(), cause,
                    actor, now);
            // one line per state transition (specs/26-observability.md §4.3); no parties or amounts
            LOG.info("payment {} txId {} {} -> {} ({}){}", next.paymentId().value(), next.txId().value(),
                    payment.state(), next.state(), cause,
                    next.reasonCode() == null ? "" : " " + next.reasonCode().code());
        }
        if (finalEvent) {
            finalEvents.emit(next);
        }
        afterCommit(() -> {
            if (next.state() == PaymentState.REJECTED) {
                telemetry.rejected(next, apply.outcome() == CsmOutcome.NOT_SENT ? RejectionCause.TECHNICAL
                        : RejectionCause.EXTERNAL);
            }
            if (finalEvent) {
                telemetry.finalised(next);
            }
        });
        if (next.pendingAction() != null) {
            runner.submitAfterCommit(next.paymentId(), false);
        }
    }

    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }

    /**
     * An event as the handler needs it.
     *
     * @param type the contract type, as in the dedup key
     * @param outcome the dedup key's outcome (02 §4): {@code RECEIVED}, the answer's status, {@code SETTLED},
     *     {@code REJECTED-<reason>}, {@code NOT_SENT}
     * @param event what an outgoing payment's outcome is; {@code null} for {@code CreditTransferReceived} and
     *     {@code AnswerSent}
     */
    private record Received(TxId txId, Bic debtorAgentBic, Direction direction, String type, String outcome,
            CsmReport event) {

        static Received of(Message message) {
            String type = Message.contractOf(message.getClass()).type();
            return switch (message) {
                case CreditTransferSettled settled -> new Received(settled.txId(), settled.debtorAgentBic(),
                        settled.direction(), type, "SETTLED", new CsmReport.Settled());
                case CreditTransferRejected rejected -> new Received(rejected.txId(), rejected.debtorAgentBic(),
                        rejected.direction(), type, "REJECTED-" + rejected.reasonCode().code(),
                        new CsmReport.Rejected(rejected.reasonCode()));
                case CreditTransferNotSent notSent -> new Received(notSent.txId(), notSent.debtorAgentBic(),
                        notSent.direction(), type, "NOT_SENT", new CsmReport.NotSent(notSent.reasonCode()));
                case CreditTransferReceived transfer -> new Received(transfer.txId(), transfer.debtorAgentBic(),
                        Direction.INCOMING, type, "RECEIVED", null);
                case AnswerSent answer -> new Received(answer.txId(), answer.debtorAgentBic(), Direction.INCOMING,
                        type, answer.status().name(), null);
                default -> throw new IllegalArgumentException("not a CSM event: " + type);
            };
        }
    }
}
