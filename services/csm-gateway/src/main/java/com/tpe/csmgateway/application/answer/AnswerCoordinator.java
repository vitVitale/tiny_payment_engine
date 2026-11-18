package com.tpe.csmgateway.application.answer;

import com.tpe.contracts.AcceptCreditTransfer;
import com.tpe.contracts.AnswerSent;
import com.tpe.contracts.AnswerStatus;
import com.tpe.contracts.DecidedBy;
import com.tpe.contracts.Direction;
import com.tpe.contracts.Message;
import com.tpe.contracts.RejectCreditTransfer;
import com.tpe.csmgateway.application.GatewayMetrics;
import com.tpe.csmgateway.application.incoming.Answers;
import com.tpe.csmgateway.application.incoming.IncomingTransfers;
import com.tpe.csmgateway.application.journal.MessageJournal;
import com.tpe.csmgateway.application.journal.MessageJournal.Entry;
import com.tpe.csmgateway.application.outgoing.CreditTransferSending;
import com.tpe.csmgateway.domain.incoming.IncomingTransfer;
import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.messaging.inbox.InboxGuard;
import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.observability.context.Spans;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.UlidGenerator;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * The answer coordinator (specs/15-csm-gateway.md §6.3, ADR-004, UC-06 6, 6b): exactly one answer per incoming
 * transaction. Two writers compete for it, each with a conditional update, so exactly one wins:
 *
 * <ul>
 *   <li>the orchestrator's verdict ({@code AcceptCreditTransfer} / {@code RejectCreditTransfer}, listener
 *       transaction, once per {@code <type>:<debtorAgentBic>:<txId>}), if no answer was decided and the deadline has
 *       not passed: {@code ACCP}, or {@code RJCT} with its reason, {@code VERDICT};
 *   <li>the deadline ({@link #deadline}, from the deadline worker), once it has passed: {@code RJCT AB05},
 *       {@code DEADLINE}.
 * </ul>
 *
 * The winner's answer {@code pacs.002} is built and journalled, and {@code AnswerSent} written to the outbox, in its
 * transaction; the answer is transmitted after the commit. A verdict that wins nothing (late, second, or for no
 * known transfer) is journalled {@code verdict ignored} and counted; no event. Events and the deadline's span are on
 * the incoming payment's trace (ADR-014).
 */
public class AnswerCoordinator {

    static final String VERDICT_IGNORED = "verdict ignored";

    /** A transfer whose answer deadline has passed with no answer: what the deadline worker hands over. */
    public record Due(Bic debtorAgentBic, TxId txId) {}

    private static final Logger log = LoggerFactory.getLogger(AnswerCoordinator.class);
    private static final String DEADLINE_SPAN = "csm.deadline.answer";

    private final IncomingTransfers transfers;
    private final Answers answers;
    private final MessageJournal journal;
    private final InboxGuard inbox;
    private final OutboxWriter outbox;
    private final GatewayMetrics metrics;
    private final Spans spans;
    private final Clock clock;
    private final UlidGenerator ulids;

    public AnswerCoordinator(IncomingTransfers transfers, Answers answers, MessageJournal journal, InboxGuard inbox,
            OutboxWriter outbox, GatewayMetrics metrics, Spans spans, Clock clock, UlidGenerator ulids) {
        this.transfers = transfers;
        this.answers = answers;
        this.journal = journal;
        this.inbox = inbox;
        this.outbox = outbox;
        this.metrics = metrics;
        this.spans = spans;
        this.clock = clock;
        this.ulids = ulids;
    }

    @Transactional
    public void accept(UUID eventId, AcceptCreditTransfer verdict) {
        verdict(eventId, verdict, verdict.debtorAgentBic(), verdict.txId(), IncomingTransfer.AnswerStatus.ACCP, null);
    }

    @Transactional
    public void reject(UUID eventId, RejectCreditTransfer verdict) {
        verdict(eventId, verdict, verdict.debtorAgentBic(), verdict.txId(), IncomingTransfer.AnswerStatus.RJCT,
                verdict.reasonCode());
    }

    /** The deadline worker's handler: in its polling transaction, the row locked. */
    public void deadline(Due due) {
        Instant now = clock.instant();
        Optional<IncomingTransfer> answered = transfers.decideByDeadline(due.debtorAgentBic(), due.txId(),
                MsgId.generate(ulids), now);
        if (answered.isEmpty()) {
            return;
        }
        IncomingTransfer transfer = answered.get();
        // always kept, always an error: the orchestrator's verdict did not come in time (specs/26-observability.md §5.2)
        Span span = (transfer.traceContext() == null ? spans.newTrace(DEADLINE_SPAN)
                : spans.childOf(DEADLINE_SPAN, transfer.traceContext())).start();
        try (Tracer.SpanInScope ignored = spans.inScope(span)) {
            span.error(new AnswerDeadlinePassed(transfer.txId()));
            log.warn("no verdict for {} from {} by its answer deadline: RJCT AB05", transfer.txId().value(),
                    transfer.debtorAgentBic().value());
            send(transfer, now);
        } finally {
            span.end();
        }
    }

    private void verdict(UUID eventId, Message verdict, Bic debtorAgentBic, TxId txId,
            IncomingTransfer.AnswerStatus status, StatusReason reason) {
        String type = Message.contractOf(verdict.getClass()).type();
        if (!inbox.firstTime(type + ":" + debtorAgentBic.value() + ":" + txId.value(),
                CreditTransferSending.COMMANDS, eventId)) {
            return;
        }
        Instant now = clock.instant();
        Optional<IncomingTransfer> answered = transfers.decideByVerdict(debtorAgentBic, txId, status, reason,
                MsgId.generate(ulids), now);
        if (answered.isEmpty()) {
            ignored(type, debtorAgentBic, txId, now);
            return;
        }
        send(answered.get(), now);
    }

    /**
     * The answer just decided for {@code transfer}, as the row records it, and its {@code AnswerSent}. The answer's
     * transmission is registered last, so a step that fails before it leaves nothing to send after the commit.
     */
    private void send(IncomingTransfer transfer, Instant now) {
        boolean accepted = transfer.answerStatus() == IncomingTransfer.AnswerStatus.ACCP;
        StatusReason reason = accepted ? null : ReasonCode.parse(transfer.answerReason());
        // in the span in scope: the verdict's listener span continues the payment's trace, the deadline's is made so
        outbox.write(CreditTransferSending.EVENTS, transfer.txId().value(), new AnswerSent(transfer.txId(),
                transfer.debtorAgentBic(), Direction.INCOMING, accepted ? AnswerStatus.ACCP : AnswerStatus.RJCT,
                reason, DecidedBy.valueOf(transfer.answerDecidedBy().name()), now));
        answers.send(transfer, answers.identityOf(transfer), accepted ? Status.ACCEPTED : Status.rejected(reason),
                now);
    }

    private void ignored(String type, Bic debtorAgentBic, TxId txId, Instant now) {
        // no lock: a row the deadline worker is about to answer must not be held up for a log line
        String why = transfers.find(txId).stream().filter(known -> known.debtorAgentBic().equals(debtorAgentBic))
                .findFirst()
                .map(known -> known.answered() ? "answered already, by " + known.answerDecidedBy()
                        : "after its answer deadline")
                .orElse("no such incoming transfer");
        log.info("{} {} for {} from {}: {}", type, VERDICT_IGNORED, txId.value(), debtorAgentBic.value(), why);
        journal.append(new Entry(MessageJournal.Direction.IN, type, null, txId, debtorAgentBic, "", true, List.of(),
                VERDICT_IGNORED, now));
        metrics.verdictIgnored();
    }

    /** The error of a {@code csm.deadline.answer} span: no verdict in time. */
    private static final class AnswerDeadlinePassed extends RuntimeException {

        AnswerDeadlinePassed(TxId txId) {
            super("no verdict for " + txId.value() + " by its answer deadline", null, false, false);
        }
    }
}
