package com.tpe.paymentorchestrator.application.outgoing;

import com.tpe.observability.context.Spans;
import com.tpe.paymentdomain.BankDirectory;
import com.tpe.paymentdomain.BankEntry;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import com.tpe.paymentdomain.UlidGenerator;
import com.tpe.paymentdomain.UuidV7Generator;
import com.tpe.paymentorchestrator.application.payments.Payments;
import com.tpe.paymentorchestrator.domain.payment.OutgoingCommand;
import com.tpe.paymentorchestrator.domain.payment.OutgoingTimings;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentParty;
import com.tpe.paymentorchestrator.domain.payment.TimerType;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code SubmitPayment} (specs/11-payment-orchestrator.md §3.1, UC-01 6): in one transaction the outgoing payment,
 * its {@code OUTGOING_DEADLINE} timer and its first transition. A {@code commandId} that already has a payment answers
 * that payment if the request is the same, and {@link CommandConflictException} if not.
 */
public class PaymentSubmission {

    /** {@code payment_transitions.cause} of a new outgoing payment. */
    static final String SUBMITTED = "SUBMITTED";

    private static final Logger LOG = LoggerFactory.getLogger(PaymentSubmission.class);

    private final Payments payments;
    private final BankDirectory directory;
    private final OutgoingTimings timings;
    private final Clock clock;
    private final UuidV7Generator paymentIds;
    private final UlidGenerator ulids;
    private final Spans spans;

    public PaymentSubmission(Payments payments, BankDirectory directory, OutgoingTimings timings, Clock clock,
            UuidV7Generator paymentIds, UlidGenerator ulids, Spans spans) {
        this.payments = payments;
        this.directory = directory;
        this.timings = timings;
        this.clock = clock;
        this.paymentIds = paymentIds;
        this.ulids = ulids;
        this.spans = spans;
    }

    /** The payment and whether this call created it. */
    public record Submitted(Payment payment, boolean created) {}

    /**
     * @param requestHash hex SHA-256 of the request; a retry has the same
     * @throws CommandConflictException if the command has a payment for another request
     * @throws CreditorAgentNotReachableException if the creditor's bank is unknown or not reachable
     */
    @Transactional
    public Submitted submit(OutgoingCommand command, String requestHash) {
        var existing = payments.findByCommand(command.commandId());
        if (existing.isPresent()) {
            return replayed(existing.get(), requestHash);
        }
        BankEntry creditorBank = directory.lookup(command.creditorIban())
                .filter(BankEntry::sctInstReachable)
                .orElseThrow(CreditorAgentNotReachableException::new);
        // as PostgreSQL stores it, so the 201 shows the acceptedAt a replay and the GET read back
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        Payment payment = Payment.submitted(new PaymentId(paymentIds.next()), command, requestHash,
                TxId.generate(ulids), new Uetr(UUID.randomUUID().toString()),
                new PaymentParty(command.debtorName(), command.debtorIban(), directory.ownBic()),
                new PaymentParty(command.creditorName(), command.creditorIban(),
                        command.creditorBic() != null ? command.creditorBic() : creditorBank.bic()),
                directory.isOwn(command.creditorIban()), now, timings, spans.currentTraceparent().orElse(null));
        if (!payments.insertIfNewCommand(payment, now)) {
            // a concurrent submit of the same command committed first
            return replayed(payments.findByCommand(command.commandId()).orElseThrow(), requestHash);
        }
        payments.scheduleTimer(payment.paymentId(), TimerType.OUTGOING_DEADLINE, payment.deadlineAt(), now);
        payments.recordTransition(payment.paymentId(), null, payment.state(), null, SUBMITTED, now);
        // one line per state transition (specs/26-observability.md §4.3); no parties or amounts
        LOG.info("payment {} txId {} -> {} ({}), onUs {}", payment.paymentId().value(), payment.txId().value(),
                payment.state(), SUBMITTED, payment.onUs());
        return new Submitted(payment, true);
    }

    private static Submitted replayed(Payment payment, String requestHash) {
        if (!payment.requestHash().equals(requestHash)) {
            throw new CommandConflictException(payment.commandId());
        }
        return new Submitted(payment, false);
    }
}
