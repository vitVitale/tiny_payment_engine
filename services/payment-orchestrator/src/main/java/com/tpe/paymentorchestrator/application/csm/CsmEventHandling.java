package com.tpe.paymentorchestrator.application.csm;

import com.tpe.contracts.CreditTransferNotSent;
import com.tpe.contracts.CreditTransferRejected;
import com.tpe.contracts.CreditTransferSettled;
import com.tpe.contracts.Direction;
import com.tpe.contracts.Message;
import com.tpe.messaging.consumer.UnknownMessageTypeException;
import com.tpe.messaging.inbox.InboxGuard;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.TxId;
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
import com.tpe.paymentorchestrator.domain.payment.OutgoingCsmEvent;
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
 * The outgoing events of {@code csm.inbound.events} (specs/11-payment-orchestrator.md §6.5), in the listener
 * transaction: inbox (key {@code <debtorAgentBic>:<txId>:<type>:<outcome>}, 02 §4) → lock the payment by its
 * transaction → apply {@link CsmEventDecision}. Recording an outcome cancels the payment's timers and sets the next
 * action, run after the commit; a contradiction opens a {@code CONTRADICTING_CSM_OUTCOME} case and changes nothing
 * else. An event for no known payment opens a case without a payment and is acknowledged. Incoming events follow
 * with the incoming flow.
 */
public class CsmEventHandling {

    /** The topic, also the inbox's {@code topic}. */
    public static final String EVENTS = "csm.inbound.events";

    private static final Logger LOG = LoggerFactory.getLogger(CsmEventHandling.class);

    private final Payments payments;
    private final InboxGuard inbox;
    private final FinalEvents finalEvents;
    private final SagaRunner runner;
    private final SagaTelemetry telemetry;
    private final Clock clock;

    public CsmEventHandling(Payments payments, InboxGuard inbox, FinalEvents finalEvents, SagaRunner runner,
            SagaTelemetry telemetry, Clock clock) {
        this.payments = payments;
        this.inbox = inbox;
        this.finalEvents = finalEvents;
        this.runner = runner;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    /**
     * @param event {@code CreditTransferSettled}, {@code CreditTransferRejected} or {@code CreditTransferNotSent}
     * @throws UnknownMessageTypeException for an incoming event, which this service does not handle yet: not
     *     retried, it goes to the DLT
     */
    @Transactional
    public void handle(UUID eventId, Message event) {
        Received received = Received.of(event);
        if (received.direction() != Direction.OUTGOING) {
            // incoming CSM events follow with M4
            throw new UnknownMessageTypeException(EVENTS, received.type() + " " + received.direction());
        }
        String dedupKey = received.debtorAgentBic().value() + ":" + received.txId().value() + ":" + received.type()
                + ":" + received.outcome();
        if (!inbox.firstTime(dedupKey, EVENTS, eventId)) {
            return;
        }
        Instant now = clock.instant();
        LockedPayment locked = payments.lockByTransaction(PaymentDirection.OUTGOING, received.debtorAgentBic(),
                received.txId()).orElse(null);
        if (locked == null) {
            LOG.warn("{} for txId {}, which is no payment of ours: case opened", received.type(),
                    received.txId().value());
            payments.openCase(null, ExceptionCaseType.DLT_MESSAGE, Map.of("cause", "UNKNOWN_TX", "topic", EVENTS,
                    "type", received.type(), "txId", received.txId().value(),
                    "debtorAgentBic", received.debtorAgentBic().value()), now);
            return;
        }
        Payment payment = locked.payment();
        switch (CsmEventDecision.decide(payment, locked.csmOutcomeRecorded(), received.event(), now)) {
            case Apply apply -> apply(locked, apply, received, now);
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

    private void apply(LockedPayment locked, Apply apply, Received received, Instant now) {
        Payment payment = locked.payment();
        Payment next = apply.next();
        boolean finalEvent = next.state().isFinal() && next.pendingAction() == null;
        payments.applyCsmOutcome(locked, next, apply.outcome(), finalEvent, now);
        if (next.state() != payment.state()) {
            payments.recordTransition(next.paymentId(), payment.state(), next.state(), next.reasonCode(),
                    apply.cause(), now);
            // one line per state transition (specs/26-observability.md §4.3); no parties or amounts
            LOG.info("payment {} txId {} {} -> {} ({}){}", next.paymentId().value(), next.txId().value(),
                    payment.state(), next.state(), apply.cause(),
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
     * @param outcome the dedup key's outcome (02 §4): {@code SETTLED}, {@code REJECTED-<reason>}, {@code NOT_SENT}
     */
    private record Received(TxId txId, Bic debtorAgentBic, Direction direction, String type, String outcome,
            OutgoingCsmEvent event) {

        static Received of(Message message) {
            String type = Message.contractOf(message.getClass()).type();
            return switch (message) {
                case CreditTransferSettled settled -> new Received(settled.txId(), settled.debtorAgentBic(),
                        settled.direction(), type, "SETTLED", new OutgoingCsmEvent.Settled());
                case CreditTransferRejected rejected -> new Received(rejected.txId(), rejected.debtorAgentBic(),
                        rejected.direction(), type, "REJECTED-" + rejected.reasonCode().code(),
                        new OutgoingCsmEvent.Rejected(rejected.reasonCode()));
                case CreditTransferNotSent notSent -> new Received(notSent.txId(), notSent.debtorAgentBic(),
                        notSent.direction(), type, "NOT_SENT", new OutgoingCsmEvent.NotSent(notSent.reasonCode()));
                default -> throw new IllegalArgumentException("not a CSM outcome: " + type);
            };
        }
    }
}
