package com.tpe.csmsimulator.application;

import com.tpe.csmsimulator.application.JournalEntry.Kind;
import com.tpe.csmsimulator.domain.scenarios.CounterpartyAccount;
import com.tpe.csmsimulator.domain.scenarios.Scenario;
import com.tpe.csmsimulator.domain.scenarios.Scenarios;
import com.tpe.csmsimulator.domain.scheme.OutgoingScheme;
import com.tpe.csmsimulator.domain.scheme.OutgoingScheme.Outcome;
import com.tpe.csmsimulator.domain.scheme.Settlement;
import com.tpe.csmsimulator.domain.scheme.Settlement.Direction;
import com.tpe.iso20022model.CsmEnvelope;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.EnvelopeCodec.Accepted;
import com.tpe.iso20022model.EnvelopeCodec.Rejected;
import com.tpe.iso20022model.Pacs002Builder;
import com.tpe.iso20022model.Pacs002Builder.StatusReport;
import com.tpe.iso20022model.XsdValidator.Violation;
import com.tpe.iso20022model.pacs008v08.CreditTransferTransaction39;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import com.tpe.paymentdomain.UlidGenerator;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The simulated CSM (specs/17-csm-simulator.md §3.1, §3.2, §3.5): receives envelopes from csm-gateway, plays the scheme
 * for our outgoing {@code pacs.008}, keeps the settlement log and the journal, and holds the control state. All state
 * is in memory; {@link #reset()} is a clean slate.
 *
 * <p>Work happens on a timeline read from the injected clock: a received message is processed at {@code now} (or
 * {@code delay} later while the transport is {@code SLOW}), an answer at the instant the scheme decides. Each piece of
 * work runs as of the instant it was due, whenever the {@link #tick()} that finds it comes; a worker ticks often, so a
 * test that moves the clock sees the effect shortly after. Thread-safe: one lock guards the state; envelopes go to the
 * gateway outside it, one after another.
 *
 * <p>The simulator is a test oracle: an envelope from the gateway that is not schema-valid, or a {@code pacs.008} it
 * cannot read, is refused with a logged error (SIM-AC-02), and every envelope it sends is validated before it leaves.
 */
public final class CsmSimulation {

    /** The violation code of a schema-valid {@code pacs.008} the simulator cannot read into domain types. */
    public static final String UNREADABLE_TRANSFER = "UNREADABLE_TRANSFER";

    private static final Logger log = LoggerFactory.getLogger(CsmSimulation.class);
    private static final Currency EUR = Currency.getInstance("EUR");
    private static final String MSG_ID_PREFIX = "SIMM";

    /** The outcome of {@link #receive(String)}. */
    public sealed interface Receipt permits Received, Refused {}

    /** The envelope is valid; it will be processed. */
    public record Received() implements Receipt {}

    /** The envelope is not a valid CSM envelope, or names a transfer the simulator cannot read; nothing was recorded. */
    public record Refused(EnvelopeCodec.Part part, List<Violation> violations) implements Receipt {}

    /** Work due {@code at}; it runs as of {@code at} and returns the envelope to send, if any. */
    private record Due(Instant at, long sequence, long generation, Function<Instant, Optional<Outbound>> work) {}

    /** An envelope to send and its journal entry, recorded once the gateway took it. */
    private record Outbound(String envelope, JournalEntry entry, long generation) {}

    private final EnvelopeCodec envelopes;
    private final OutgoingScheme scheme;
    private final Pacs002Builder statusReports;
    private final GatewayClient gateway;
    private final Clock clock;
    private final UlidGenerator ulids;
    private final Bic ownBic;

    private final Object lock = new Object();
    private final Scenarios scenarios = new Scenarios();
    private final Map<Iban, CounterpartyAccount> counterpartyAccounts = new HashMap<>();
    private final Set<String> processedMsgIds = new HashSet<>();
    private final List<JournalEntry> journal = new ArrayList<>();
    private final List<Settlement> settlements = new ArrayList<>();
    private final PriorityQueue<Due> timeline =
            new PriorityQueue<>(Comparator.comparing(Due::at).thenComparingLong(Due::sequence));
    private long sequence;
    /** Raised by {@link #reset()}: work and envelopes from before it are dropped after it. */
    private long generation;
    private Outage outage = Outage.NONE;

    /** @param ownBic the CSM's BIC, {@code tpe.sim.own-bic}: the instructing agent of the status reports it sends */
    public CsmSimulation(EnvelopeCodec envelopes, OutgoingScheme scheme, Pacs002Builder statusReports,
            GatewayClient gateway, Clock clock, UlidGenerator ulids, Bic ownBic) {
        this.envelopes = Objects.requireNonNull(envelopes, "envelopes");
        this.scheme = Objects.requireNonNull(scheme, "scheme");
        this.statusReports = Objects.requireNonNull(statusReports, "statusReports");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ulids = Objects.requireNonNull(ulids, "ulids");
        this.ownBic = Objects.requireNonNull(ownBic, "ownBic");
    }

    /**
     * An envelope from csm-gateway ({@code POST /csm/v1/messages}). A valid one is journalled and, unless its
     * {@code MsgId} was processed already, processed later on the timeline.
     */
    public Receipt receive(String xml) {
        EnvelopeCodec.SplitResult split = envelopes.split(xml);
        if (split instanceof Rejected rejected) {
            return refuse(rejected.part(), rejected.violations());
        }
        CsmEnvelope envelope = ((Accepted) split).envelope();
        CsmMessage message = CsmMessage.of(envelope);
        CreditTransfer transfer = null;
        if (envelope.messageType() == CsmMessageType.PACS_008) {
            try {
                transfer = CreditTransfer.of(envelope);
            } catch (RuntimeException e) {
                return refuse(EnvelopeCodec.Part.DOCUMENT,
                        List.of(new Violation(0, 0, UNREADABLE_TRANSFER, e.toString())));
            }
        }
        synchronized (lock) {
            Instant now = clock.instant();
            boolean duplicate = !processedMsgIds.add(message.msgId());
            journal.add(new JournalEntry(message.txId(), Kind.RECEIVED, envelope.messageType().version(),
                    message.msgId(), now, duplicate, xml));
            if (!duplicate && transfer != null) {
                CreditTransfer received = transfer;
                schedule(now.plus(outage.delay()), at -> processCreditTransfer(received, at));
            }
        }
        return new Received();
    }

    /** Runs the work that is due, then sends what it produced to the gateway. */
    public void tick() {
        List<Outbound> outbound = new ArrayList<>();
        synchronized (lock) {
            Instant now = clock.instant();
            while (!timeline.isEmpty() && !timeline.peek().at().isAfter(now)) {
                Due due = timeline.poll();
                if (due.generation() != generation) {
                    continue;
                }
                try {
                    due.work().apply(due.at()).ifPresent(outbound::add);
                } catch (RuntimeException e) {
                    // one broken piece of work must not take the others with it
                    log.error("simulator work due at {} failed", due.at(), e);
                }
            }
        }
        for (Outbound message : outbound) {
            send(message);
        }
    }

    public void setScenario(Iban creditor, Scenario scenario) {
        synchronized (lock) {
            scenarios.set(creditor, scenario);
        }
    }

    /** Replaces the counterparty accounts. */
    public void setCounterpartyAccounts(List<CounterpartyAccount> accounts) {
        synchronized (lock) {
            counterpartyAccounts.clear();
            accounts.forEach(account -> counterpartyAccounts.put(account.iban(), account));
        }
    }

    /** Every message received, or sent and taken by the gateway, for {@code txId}, oldest first. */
    public List<JournalEntry> journal(TxId txId) {
        synchronized (lock) {
            return journal.stream().filter(entry -> txId.equals(entry.txId())).toList();
        }
    }

    /** The settlement log from {@code since} on (inclusive), oldest first. */
    public List<Settlement> settlements(Instant since) {
        synchronized (lock) {
            return settlements.stream().filter(s -> !s.settledAt().isBefore(since)).toList();
        }
    }

    public Outage outage() {
        synchronized (lock) {
            return outage;
        }
    }

    public void setOutage(Outage outage) {
        synchronized (lock) {
            this.outage = Objects.requireNonNull(outage, "outage");
        }
    }

    /** Clears all state: scenarios, accounts, journal, settlements, pending work and the outage. */
    public void reset() {
        synchronized (lock) {
            scenarios.clear();
            counterpartyAccounts.clear();
            processedMsgIds.clear();
            journal.clear();
            settlements.clear();
            timeline.clear();
            generation++;
            outage = Outage.NONE;
        }
    }

    private static Refused refuse(EnvelopeCodec.Part part, List<Violation> violations) {
        // the simulator is a test oracle (SIM-AC-02): an invalid message from us must fail tests loudly
        log.error("refused an invalid {} from csm-gateway: {}", part,
                violations.stream().map(v -> v.code() + "@" + v.line() + ":" + v.column()).toList());
        return new Refused(part, violations);
    }

    /** Runs under the lock, as of {@code now}. */
    private Optional<Outbound> processCreditTransfer(CreditTransfer transfer, Instant now) {
        Scenario scenario = scenarios.scenarioFor(transfer.creditor(), transfer.amount());
        Outcome outcome = scheme.decide(transfer.acceptedAt(), scenario, now);
        schedule(outcome.at(), at -> answer(transfer, outcome, at));
        return Optional.empty();
    }

    /** Runs under the lock, as of {@code now}: builds the status report first, so a settlement always has one. */
    private Optional<Outbound> answer(CreditTransfer transfer, Outcome outcome, Instant now) {
        MsgId msgId = new MsgId(MSG_ID_PREFIX + ulids.next());
        CsmEnvelope report = statusReports.build(new StatusReport(msgId, now, transfer.msgId(),
                CsmMessageType.PACS_008, transfer.txId(), transfer.endToEndId(), transfer.uetr(),
                transfer.debtorAgent(), outcome.status(), ownBic, transfer.debtorAgent()));
        // join validates both parts: everything the simulator sends is schema-valid (SIM-AC-02)
        String xml = envelopes.join(report);
        if (outcome.settles()) {
            settlements.add(new Settlement(transfer.txId(), transfer.debtorAgent(), Direction.OUTGOING,
                    transfer.amount().minor(), now));
        }
        return Optional.of(new Outbound(xml, new JournalEntry(transfer.txId(), Kind.SENT,
                CsmMessageType.PACS_002.version(), msgId.value(), now, false, xml), generation));
    }

    /** Sends outside the lock; a send the gateway does not take is logged, not repeated, and not journalled. */
    private void send(Outbound message) {
        synchronized (lock) {
            if (message.generation() != generation) {
                return;
            }
        }
        try {
            gateway.send(message.envelope());
        } catch (RuntimeException e) {
            log.warn("csm-gateway did not take {} {}: {}", message.entry().messageType(), message.entry().msgId(),
                    e.toString());
            return;
        }
        synchronized (lock) {
            if (message.generation() == generation) {
                journal.add(message.entry());
            }
        }
    }

    private void schedule(Instant at, Function<Instant, Optional<Outbound>> work) {
        timeline.add(new Due(at, sequence++, generation, work));
    }

    /** The {@code MsgId} of a message and the transaction it is about ({@code null} if it names none readable). */
    private record CsmMessage(String msgId, TxId txId) {

        static CsmMessage of(CsmEnvelope envelope) {
            return switch (envelope.messageType()) {
                case PACS_008 -> {
                    var message = ((com.tpe.iso20022model.pacs008v08.Document) envelope.document())
                            .getFIToFICstmrCdtTrf();
                    yield new CsmMessage(message.getGrpHdr().getMsgId(),
                            txId(message.getCdtTrfTxInf().getFirst().getPmtId().getTxId()));
                }
                case PACS_002 -> {
                    var message = ((com.tpe.iso20022model.pacs002v10.Document) envelope.document())
                            .getFIToFIPmtStsRpt();
                    yield new CsmMessage(message.getGrpHdr().getMsgId(), message.getTxInfAndSts().stream()
                            .findFirst().map(t -> txId(t.getOrgnlTxId())).orElse(null));
                }
                case PACS_028 -> {
                    var message = ((com.tpe.iso20022model.pacs028v03.Document) envelope.document())
                            .getFIToFIPmtStsReq();
                    yield new CsmMessage(message.getGrpHdr().getMsgId(), message.getTxInf().stream()
                            .findFirst().map(t -> txId(t.getOrgnlTxId())).orElse(null));
                }
            };
        }

        private static TxId txId(String value) {
            try {
                return value == null ? null : new TxId(value);
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** What the scheme needs from a {@code pacs.008}, read into domain types. */
    private record CreditTransfer(MsgId msgId, TxId txId, EndToEndId endToEndId, Uetr uetr, Money amount,
            Instant acceptedAt, Bic debtorAgent, Iban creditor) {

        /** @throws RuntimeException if a field the scheme needs is missing or not a valid domain value */
        static CreditTransfer of(CsmEnvelope envelope) {
            var message = ((com.tpe.iso20022model.pacs008v08.Document) envelope.document()).getFIToFICstmrCdtTrf();
            CreditTransferTransaction39 transaction = message.getCdtTrfTxInf().getFirst();
            var settlementAmount = transaction.getIntrBkSttlmAmt();
            if (!EUR.getCurrencyCode().equals(settlementAmount.getCcy())) {
                throw new IllegalArgumentException("not EUR: " + settlementAmount.getCcy());
            }
            return new CreditTransfer(
                    new MsgId(message.getGrpHdr().getMsgId()),
                    new TxId(transaction.getPmtId().getTxId()),
                    new EndToEndId(transaction.getPmtId().getEndToEndId()),
                    new Uetr(transaction.getPmtId().getUETR()),
                    Money.parse(settlementAmount.getValue().toPlainString()),
                    transaction.getAccptncDtTm().toGregorianCalendar().toInstant(),
                    new Bic(transaction.getDbtrAgt().getFinInstnId().getBICFI()),
                    new Iban(transaction.getCdtrAcct().getId().getIBAN()));
        }
    }
}
