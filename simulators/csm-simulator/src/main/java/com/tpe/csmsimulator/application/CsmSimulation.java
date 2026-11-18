package com.tpe.csmsimulator.application;

import com.tpe.csmsimulator.application.JournalEntry.Kind;
import com.tpe.csmsimulator.domain.scenarios.CounterpartyAccount;
import com.tpe.csmsimulator.domain.scenarios.IncomingScenario;
import com.tpe.csmsimulator.domain.scenarios.Scenario;
import com.tpe.csmsimulator.domain.scenarios.Scenarios;
import com.tpe.csmsimulator.domain.scheme.IncomingScheme;
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
import com.tpe.iso20022model.Pacs008Builder;
import com.tpe.iso20022model.Pacs008Builder.AccountHolder;
import com.tpe.iso20022model.XsdValidator.Violation;
import com.tpe.iso20022model.pacs002v10.StatusReason6Choice;
import com.tpe.iso20022model.pacs002v10.StatusReasonInformation12;
import com.tpe.iso20022model.pacs008v08.CreditTransferTransaction39;
import com.tpe.paymentdomain.BankDirectory;
import com.tpe.paymentdomain.BankEntry;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import com.tpe.paymentdomain.UlidGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The simulated CSM (specs/17-csm-simulator.md §3.1, §3.2, §3.5): receives envelopes from csm-gateway, plays the scheme
 * for our outgoing {@code pacs.008}, delivers incoming {@code pacs.008} to us and settles the ones we accept in time,
 * answers status investigations ({@code pacs.028}) with the final status it knows, keeps the settlement log and the
 * journal, and holds the control state. All state is in memory; {@link #reset()} is
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
    private static final String TX_ID_PREFIX = "SIMT";
    private static final String END_TO_END_ID_PREFIX = "SIME";
    /** Incoming payments are to accounts of ours; the CSM does not know their holders' names. */
    private static final PartyName CREDITOR_NAME = new PartyName("Account Holder");

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

    /**
     * An incoming payment the CSM delivered to the gateway at {@code transfer.createdAt()}; {@code finalStatus} is
     * {@code null} while the CSM waits for our answer.
     */
    private record Incoming(Pacs008Builder.CreditTransfer transfer, IncomingScenario scenario, Status finalStatus) {

        boolean awaitingAnswer() {
            return finalStatus == null;
        }

        Incoming concluded(Status status) {
            return new Incoming(transfer, scenario, status);
        }

        /** Whether {@code entry} journals this payment's {@code pacs.008}. */
        boolean sentAs(JournalEntry entry) {
            return transfer.msgId().value().equals(entry.msgId());
        }
    }

    private final EnvelopeCodec envelopes;
    private final OutgoingScheme scheme;
    private final IncomingScheme incomingScheme;
    private final Pacs002Builder statusReports;
    private final Pacs008Builder creditTransfers = new Pacs008Builder();
    private final GatewayClient gateway;
    private final Clock clock;
    private final UlidGenerator ulids;
    private final Bic ownBic;
    private final BankDirectory banks;

    private final Object lock = new Object();
    private final Scenarios scenarios = new Scenarios();
    private final Map<Iban, CounterpartyAccount> counterpartyAccounts = new HashMap<>();
    private final Set<String> processedMsgIds = new HashSet<>();
    private final Map<TransactionKey, Transaction> transactions = new HashMap<>();
    private final Map<TransactionKey, Incoming> incoming = new HashMap<>();
    private final List<JournalEntry> journal = new ArrayList<>();
    private final List<Settlement> settlements = new ArrayList<>();
    private final PriorityQueue<Due> timeline =
            new PriorityQueue<>(Comparator.comparing(Due::at).thenComparingLong(Due::sequence));
    private long sequence;
    /** Raised by {@link #reset()}: work and envelopes from before it are dropped after it. */
    private long generation;
    private Outage outage = Outage.NONE;

    /**
     * @param ownBic the CSM's BIC, {@code tpe.sim.own-bic}: the instructing agent of the status reports it sends
     * @param banks our BIC (the creditor agent of incoming payments) and the counterparty banks (their debtor agent)
     */
    public CsmSimulation(EnvelopeCodec envelopes, OutgoingScheme scheme, IncomingScheme incomingScheme,
            Pacs002Builder statusReports, GatewayClient gateway, Clock clock, UlidGenerator ulids, Bic ownBic,
            BankDirectory banks) {
        this.envelopes = Objects.requireNonNull(envelopes, "envelopes");
        this.scheme = Objects.requireNonNull(scheme, "scheme");
        this.incomingScheme = Objects.requireNonNull(incomingScheme, "incomingScheme");
        this.statusReports = Objects.requireNonNull(statusReports, "statusReports");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ulids = Objects.requireNonNull(ulids, "ulids");
        this.ownBic = Objects.requireNonNull(ownBic, "ownBic");
        this.banks = Objects.requireNonNull(banks, "banks");
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
                case PACS_002 -> {
                    Answer answer = Answer.of(envelope);
                    yield at -> answer(answer, at);
                }
            };
        } catch (RuntimeException e) {
            return refuse(EnvelopeCodec.Part.DOCUMENT, List.of(new Violation(0, 0, UNREADABLE_MESSAGE, e.toString())));
        }
        synchronized (lock) {
            Instant now = clock.instant();
            boolean duplicate = !processedMsgIds.add(message.msgId());
            journal.add(new JournalEntry(message.txId(), Kind.RECEIVED, envelope.messageType().version(),
                    message.msgId(), now, duplicate, xml));
            if (!duplicate) {
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

    /**
     * Sends a new incoming {@code pacs.008} to the gateway ({@code POST /sim/v1/incoming-payments}) and waits for our
     * answer until the answer deadline. It is journalled as sent before it goes, so our answer, which may come before
     * the gateway's response, follows it in the journal; a payment the gateway refuses ({@code non-2xx}) is forgotten.
     *
     * @throws InvalidIncomingPaymentException if it has no debtor agent, or its transaction exists already
     * @throws GatewayUnreachableException if the gateway gave no answer; the payment is kept, as the gateway may have
     *     taken it
     */
    public IncomingPaymentSent sendIncomingPayment(IncomingPayment payment) {
        Pacs008Builder.CreditTransfer transfer;
        TransactionKey key;
        JournalEntry entry;
        synchronized (lock) {
            Instant now = clock.instant();
            Bic debtorAgent = payment.debtorAgent() != null ? payment.debtorAgent()
                    : banks.lookup(payment.debtorIban()).map(BankEntry::bic).orElseThrow(
                            () -> new InvalidIncomingPaymentException("debtorAgentBic",
                                    "no bank in the directory for the debtor IBAN; give debtorAgentBic"));
            TxId txId = payment.txId() != null ? payment.txId() : new TxId(TX_ID_PREFIX + ulids.next());
            key = new TransactionKey(debtorAgent, txId);
            if (incoming.containsKey(key)) {
                throw new InvalidIncomingPaymentException("txId",
                        "an incoming payment " + txId.value() + " from " + debtorAgent.value() + " exists already");
            }
            transfer = new Pacs008Builder.CreditTransfer(new MsgId(MSG_ID_PREFIX + ulids.next()), now, txId,
                    new EndToEndId(END_TO_END_ID_PREFIX + ulids.next()), new Uetr(UUID.randomUUID().toString()),
                    payment.amount(), LocalDate.ofInstant(now, ZoneOffset.UTC), now,
                    new AccountHolder(payment.debtorName(), payment.debtorIban()), debtorAgent, banks.ownBic(),
                    new AccountHolder(CREDITOR_NAME, payment.creditor()), null);
            // join validates both parts: everything the simulator sends is schema-valid (SIM-AC-02)
            String xml = envelopes.join(creditTransfers.build(transfer));
            entry = new JournalEntry(txId, Kind.SENT, CsmMessageType.PACS_008.version(), transfer.msgId().value(),
                    now, false, xml);
            incoming.put(key, new Incoming(transfer, payment.scenario(), null));
            journal.add(entry);
            MsgId msgId = transfer.msgId();
            schedule(incomingScheme.answerDeadline(now), at -> answerTimedOut(key, msgId));
        }
        int status;
        try {
            status = gateway.post(entry.envelope());
        } catch (RuntimeException e) {
            // kept: the gateway may have taken it before the connection failed, and then answers it
            throw new GatewayUnreachableException(e);
        }
        if (!GatewayClient.isSuccess(status)) {
            log.warn("csm-gateway did not take incoming {} of {}: {}", transfer.msgId().value(),
                    transfer.txId().value(), status);
            forget(key, entry);
        }
        return new IncomingPaymentSent(transfer.txId(), status);
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
            incoming.clear();
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

    /** Drops an incoming payment the gateway did not take; its answer deadline then finds nothing to time out. */
    private void forget(TransactionKey key, JournalEntry sent) {
        synchronized (lock) {
            Incoming forgotten = incoming.get(key);
            if (forgotten != null && forgotten.sentAs(sent)) {
                incoming.remove(key);
                journal.remove(sent);
            }
        }
    }

    /**
     * Runs under the lock, as of {@code now}: our answer to an incoming payment. An {@code ACCP} in time is settled
     * and confirmed with a {@code pacs.002 ACSC}, rejected {@code AB05} or settled unconfirmed, as the payment's
     * scenario says; a {@code RJCT} in time is recorded; anything else (late, a second answer, one naming no payment
     * the CSM sent) is only journalled.
     */
    private Optional<Outbound> answer(Answer answer, Instant now) {
        Optional<Map.Entry<TransactionKey, Incoming>> found = incoming.entrySet().stream()
                .filter(e -> answer.answers(e.getKey(), e.getValue().transfer().msgId()))
                .findFirst();
        if (found.isEmpty()) {
            log.info("answer {} of {} names no incoming payment", answer.msgId().value(), answer.txId().value());
            return Optional.empty();
        }
        TransactionKey key = found.get().getKey();
        Incoming payment = found.get().getValue();
        Pacs008Builder.CreditTransfer transfer = payment.transfer();
        if (!payment.awaitingAnswer()) {
            log.info("answer {} ({}) of {} changes nothing: final already, {}", answer.msgId().value(),
                    answer.status(), transfer.txId().value(), payment.finalStatus());
            return Optional.empty();
        }
        Optional<IncomingScheme.Outcome> decided = incomingScheme.answer(transfer.createdAt(), payment.scenario(),
                answer.status(), answer.reason(), now);
        if (decided.isEmpty()) {
            log.info("answer {} ({}) of {} changes nothing: late, or not ACCP / RJCT", answer.msgId().value(),
                    answer.status(), transfer.txId().value());
            return Optional.empty();
        }
        IncomingScheme.Outcome outcome = decided.get();
        incoming.put(key, payment.concluded(outcome.finalStatus()));
        if (outcome.settles()) {
            settlements.add(new Settlement(transfer.txId(), transfer.debtorAgent(), Direction.INCOMING,
                    transfer.amount().minor(), now));
        }
        if (!outcome.reported()) {
            return Optional.empty();
        }
        return Optional.of(statusReport(transfer.txId(), now, id -> new StatusReport(id, now, transfer.msgId(),
                CsmMessageType.PACS_008, transfer.txId(), transfer.endToEndId(), transfer.uetr(),
                transfer.debtorAgent(), outcome.finalStatus(), ownBic, transfer.creditorAgent())));
    }

    /** Runs under the lock: the CSM stops waiting for our answer, rejects the payment and sends us nothing. */
    private Optional<Outbound> answerTimedOut(TransactionKey key, MsgId msgId) {
        Incoming payment = incoming.get(key);
        if (payment != null && payment.awaitingAnswer() && payment.transfer().msgId().equals(msgId)) {
            incoming.put(key, payment.concluded(incomingScheme.timedOut()));
            log.info("no answer to incoming {} of {} in time", msgId.value(), key.txId().value());
        }
        return Optional.empty();
    }

    /** The {@code pacs.002} answering the {@code pacs.008}. */
    private Outbound report(CreditTransfer transfer, Status status, Instant now) {
        return statusReport(transfer.txId(), now, id -> new StatusReport(id, now, transfer.msgId(),
                CsmMessageType.PACS_008, transfer.txId(), transfer.endToEndId(), transfer.uetr(),
                transfer.debtorAgent(), status, ownBic, transfer.debtorAgent()));
    }

    /**
     * Runs under the lock, as of {@code now}: answers with the final status of the transaction, outgoing or incoming,
     * {@code RJCT UNKNOWN_TX} if the CSM never received or sent it, nothing while it has no final status yet or the
     * creditor's scenario of an outgoing one ignores investigations.
     */
    private Optional<Outbound> investigate(StatusRequest request, Instant now) {
        Transaction outgoing = transactions.get(request.key());
        Incoming delivered = incoming.get(request.key());
        Status status;
        if (outgoing != null) {
            CreditTransfer transfer = outgoing.transfer();
            Scenario scenario = scenarios.scenarioFor(transfer.creditor(), transfer.amount());
            if (scenario.ignoresInvestigations()) {
                log.info("ignored investigation {} of {}: scenario {}", request.msgId().value(),
                        request.txId().value(), scenario.text());
                return Optional.empty();
            }
            status = outgoing.finalStatus();
        } else if (delivered != null) {
            status = delivered.finalStatus();
        } else {
            status = Status.UNKNOWN_TRANSACTION;
        }
        if (status == null) {
            log.info("investigation {} of {} unanswered: no final status yet", request.msgId().value(),
                    request.txId().value());
            return Optional.empty();
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

    /**
     * Our answer to an incoming payment, a {@code pacs.002} read into domain types. It names the transaction by
     * {@code OrgnlTxRef/DbtrAgt} and {@code OrgnlTxId} (ADR-010), or, without a debtor agent, by {@code OrgnlTxId}
     * and {@code OrgnlMsgId}.
     *
     * @param originalMsgId {@code null} when the answer carries none
     * @param debtorAgent {@code null} when the answer carries none
     * @param reason the {@code StsRsnInf/Rsn/Cd} of a {@code RJCT}, {@code null} for any other status
     */
    private record Answer(MsgId msgId, MsgId originalMsgId, TxId txId, Bic debtorAgent, String status,
            StatusReason reason) {

        /**
         * @throws RuntimeException if a field the CSM needs is missing or not a valid domain value; a {@code RJCT}
         *     needs a reason code, as the CSM answers investigations with it
         */
        static Answer of(CsmEnvelope envelope) {
            var message = ((com.tpe.iso20022model.pacs002v10.Document) envelope.document()).getFIToFIPmtStsRpt();
            var transaction = message.getTxInfAndSts().getFirst();
            var reference = transaction.getOrgnlTxRef();
            Bic debtorAgent = reference == null || reference.getDbtrAgt() == null ? null
                    : new Bic(reference.getDbtrAgt().getFinInstnId().getBICFI());
            MsgId originalMsgId = message.getOrgnlGrpInfAndSts().stream().findFirst()
                    .map(group -> new MsgId(group.getOrgnlMsgId())).orElse(null);
            String status = Objects.requireNonNull(transaction.getTxSts(), "TxSts");
            StatusReason reason = null;
            if (IncomingScheme.REJECTED.equals(status)) {
                String code = transaction.getStsRsnInf().stream().findFirst()
                        .map(StatusReasonInformation12::getRsn).map(StatusReason6Choice::getCd)
                        .orElseThrow(() -> new IllegalArgumentException("RJCT without Rsn/Cd"));
                reason = ReasonCode.parse(code);
            }
            return new Answer(new MsgId(message.getGrpHdr().getMsgId()), originalMsgId,
                    new TxId(transaction.getOrgnlTxId()), debtorAgent, status, reason);
        }

        boolean answers(TransactionKey key, MsgId incomingMsgId) {
            return debtorAgent != null ? key.equals(new TransactionKey(debtorAgent, txId))
                    : key.txId().equals(txId) && incomingMsgId.equals(originalMsgId);
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
