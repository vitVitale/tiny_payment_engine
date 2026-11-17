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
import com.tpe.iso20022model.Pacs002Builder.Status;
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
 * for our outgoing {@code pacs.008}, answers status investigations ({@code pacs.028}) with the final status it knows,
 * keeps the settlement log and the journal, and holds the control state. All state is in memory; {@link #reset()} is
 * a clean slate.
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

    /** The violation code of a schema-valid {@code pacs.008} or {@code pacs.028} the simulator cannot read. */
    public static final String UNREADABLE_MESSAGE = "UNREADABLE_MESSAGE";

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

    /** A transaction is identified by its debtor agent and {@code TxId} (ADR-010). */
    private record TransactionKey(Bic debtorAgent, TxId txId) {}

    /** A transaction the CSM received; {@code finalStatus} is {@code null} until it has one. */
    private record Transaction(CreditTransfer transfer, Status finalStatus) {}

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
    private final Map<TransactionKey, Transaction> transactions = new HashMap<>();
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
        Function<Instant, Optional<Outbound>> work;
        try {
            work = switch (envelope.messageType()) {
                case PACS_008 -> {
                    CreditTransfer transfer = CreditTransfer.of(envelope);
                    yield at -> processCreditTransfer(transfer, at);
                }
                case PACS_028 -> {
                    StatusRequest request = StatusRequest.of(envelope);
                    yield at -> investigate(request, at);
                }
                case PACS_002 -> null;
            };
        } catch (RuntimeException e) {
            return refuse(EnvelopeCodec.Part.DOCUMENT, List.of(new Violation(0, 0, UNREADABLE_MESSAGE, e.toString())));
        }
        synchronized (lock) {
            Instant now = clock.instant();
            boolean duplicate = !processedMsgIds.add(message.msgId());
            journal.add(new JournalEntry(message.txId(), Kind.RECEIVED, envelope.messageType().version(),
                    message.msgId(), now, duplicate, xml));
            if (!duplicate && work != null) {
                schedule(now.plus(outage.delay()), work);
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

    /**
     * Sends {@code message} to the gateway as it is ({@code POST /sim/v1/incoming-payments/raw}): no validation, so a
     * test can send an invalid or contradicting one. Journalled as sent when the gateway took it and it is a CSM
     * envelope that names a transaction.
     *
     * @return the gateway's HTTP status
     * @throws GatewayUnreachableException if the gateway gave no answer
     */
    public int sendRaw(String message) {
        int status;
        try {
            status = gateway.post(message);
        } catch (RuntimeException e) {
            throw new GatewayUnreachableException(e);
        }
        if (!GatewayClient.isSuccess(status)) {
            return status;
        }
        if (envelopes.split(message) instanceof Accepted accepted
                && CsmMessage.of(accepted.envelope()) instanceof CsmMessage sent && sent.txId() != null) {
            synchronized (lock) {
                journal.add(new JournalEntry(sent.txId(), Kind.SENT, accepted.envelope().messageType().version(),
                        sent.msgId(), clock.instant(), false, message));
            }
        }
        return status;
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

    /**
     * Clears all state: scenarios, accounts, the transactions received, journal, settlements, pending work and the
     * outage.
     */
    public void reset() {
        synchronized (lock) {
            scenarios.clear();
            counterpartyAccounts.clear();
            processedMsgIds.clear();
            transactions.clear();
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

    /** Runs under the lock, as of {@code now}. A {@code LOST} transfer is dropped: the CSM never received it. */
    private Optional<Outbound> processCreditTransfer(CreditTransfer transfer, Instant now) {
        Scenario scenario = scenarios.scenarioFor(transfer.creditor(), transfer.amount());
        Optional<Outcome> decided = scheme.decide(transfer.acceptedAt(), scenario, now);
        if (decided.isEmpty()) {
            log.info("dropped {} of {}: scenario {}", transfer.msgId().value(), transfer.txId().value(),
                    scenario.text());
            return Optional.empty();
        }
        Outcome outcome = decided.get();
        transactions.put(transfer.key(), new Transaction(transfer, null));
        schedule(outcome.at(), at -> conclude(transfer, outcome, at));
        return Optional.empty();
    }

    /**
     * Runs under the lock, as of {@code now}: the status is final, the transfer settled if it settles, and the status
     * reported now, later or never. A report due now is built first, so a reported settlement always has one.
     */
    private Optional<Outbound> conclude(CreditTransfer transfer, Outcome outcome, Instant now) {
        Optional<Outbound> report = Optional.empty();
        if (now.equals(outcome.reportAt())) {
            report = Optional.of(report(transfer, outcome.status(), now));
        } else if (outcome.reportAt() != null) {
            schedule(outcome.reportAt(), at -> Optional.of(report(transfer, outcome.status(), at)));
        }
        if (outcome.settles()) {
            settlements.add(new Settlement(transfer.txId(), transfer.debtorAgent(), Direction.OUTGOING,
                    transfer.amount().minor(), now));
        }
        transactions.put(transfer.key(), new Transaction(transfer, outcome.status()));
        return report;
    }

    /** The {@code pacs.002} answering the {@code pacs.008}. */
    private Outbound report(CreditTransfer transfer, Status status, Instant now) {
        return statusReport(transfer.txId(), now, id -> new StatusReport(id, now, transfer.msgId(),
                CsmMessageType.PACS_008, transfer.txId(), transfer.endToEndId(), transfer.uetr(),
                transfer.debtorAgent(), status, ownBic, transfer.debtorAgent()));
    }

    /**
     * Runs under the lock, as of {@code now}: answers with the final status of the transaction, {@code RJCT
     * UNKNOWN_TX} if the CSM never received it, nothing while it has no final status yet or its creditor's scenario
     * ignores investigations.
     */
    private Optional<Outbound> investigate(StatusRequest request, Instant now) {
        Transaction transaction = transactions.get(request.key());
        Status status;
        if (transaction == null) {
            status = Status.UNKNOWN_TRANSACTION;
        } else {
            CreditTransfer transfer = transaction.transfer();
            Scenario scenario = scenarios.scenarioFor(transfer.creditor(), transfer.amount());
            if (scenario.ignoresInvestigations()) {
                log.info("ignored investigation {} of {}: scenario {}", request.msgId().value(),
                        request.txId().value(), scenario.text());
                return Optional.empty();
            }
            if (transaction.finalStatus() == null) {
                log.info("investigation {} of {} unanswered: no final status yet", request.msgId().value(),
                        request.txId().value());
                return Optional.empty();
            }
            status = transaction.finalStatus();
        }
        return Optional.of(statusReport(request.txId(), now, id -> new StatusReport(id, now, request.msgId(),
                CsmMessageType.PACS_028, request.txId(), request.endToEndId(), request.uetr(), request.debtorAgent(),
                status, ownBic, request.instructingAgent())));
    }

    /** A status report under a new {@code MsgId}, ready to send. */
    private Outbound statusReport(TxId txId, Instant now, Function<MsgId, StatusReport> report) {
        MsgId msgId = new MsgId(MSG_ID_PREFIX + ulids.next());
        // join validates both parts: everything the simulator sends is schema-valid (SIM-AC-02)
        String xml = envelopes.join(statusReports.build(report.apply(msgId)));
        return new Outbound(xml, new JournalEntry(txId, Kind.SENT, CsmMessageType.PACS_002.version(), msgId.value(),
                now, false, xml), generation);
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

        TransactionKey key() {
            return new TransactionKey(debtorAgent, txId);
        }

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

    /** What an investigation needs from a {@code pacs.028}, read into domain types. */
    private record StatusRequest(MsgId msgId, TxId txId, EndToEndId endToEndId, Uetr uetr, Bic debtorAgent,
            Bic instructingAgent) {

        /** @throws RuntimeException if a field the answer needs is missing or not a valid domain value */
        static StatusRequest of(CsmEnvelope envelope) {
            var message = ((com.tpe.iso20022model.pacs028v03.Document) envelope.document()).getFIToFIPmtStsReq();
            var transaction = message.getTxInf().getFirst();
            return new StatusRequest(
                    new MsgId(message.getGrpHdr().getMsgId()),
                    new TxId(transaction.getOrgnlTxId()),
                    transaction.getOrgnlEndToEndId() == null ? null : new EndToEndId(transaction.getOrgnlEndToEndId()),
                    transaction.getOrgnlUETR() == null ? null : new Uetr(transaction.getOrgnlUETR()),
                    new Bic(transaction.getOrgnlTxRef().getDbtrAgt().getFinInstnId().getBICFI()),
                    new Bic(transaction.getInstgAgt().getFinInstnId().getBICFI()));
        }

        TransactionKey key() {
            return new TransactionKey(debtorAgent, txId);
        }
    }
}
