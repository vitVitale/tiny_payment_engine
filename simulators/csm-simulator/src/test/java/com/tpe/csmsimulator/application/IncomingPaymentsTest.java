package com.tpe.csmsimulator.application;

import static com.tpe.csmsimulator.CreditTransfers.CSM_BIC;
import static com.tpe.csmsimulator.CreditTransfers.OUR_BIC;
import static com.tpe.csmsimulator.CreditTransfers.PEER_BIC;
import static com.tpe.csmsimulator.CreditTransfers.answer;
import static com.tpe.csmsimulator.CreditTransfers.pacs008;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.tpe.csmsimulator.application.JournalEntry.Kind;
import com.tpe.csmsimulator.domain.scenarios.Scenario;
import com.tpe.csmsimulator.domain.scheme.IncomingScheme;
import com.tpe.csmsimulator.domain.scheme.OutgoingScheme;
import com.tpe.csmsimulator.domain.scheme.Settlement;
import com.tpe.csmsimulator.domain.scheme.Settlement.Direction;
import com.tpe.iso20022model.CsmEnvelope;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.EnvelopeCodec.Accepted;
import com.tpe.iso20022model.Pacs002Builder;
import com.tpe.iso20022model.Pacs002Builder.Status;
import com.tpe.iso20022model.pacs002v10.FIToFIPaymentStatusReportV10;
import com.tpe.iso20022model.pacs008v08.FIToFICustomerCreditTransferV08;
import com.tpe.paymentdomain.BankEntry;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.InMemoryBankDirectory;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.MutableClock;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.UlidGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Incoming payments (specs/17-csm-simulator.md §3.2, incoming): the CSM delivers, waits for our answer, settles. */
class IncomingPaymentsTest {

    private static final Instant START = Instant.parse("2026-11-15T10:00:00.000Z");
    private static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(6);
    private static final Duration BENEFICIARY_DELAY = Duration.ofMillis(300);
    /** An account of ours (bank code 10010010). */
    private static final Iban OUR_ACCOUNT = new Iban("DE36100100100000000001");
    /** An account at the peer bank (bank code 20020020, {@code PEERDEFFXXX}). */
    private static final Iban PEER_ACCOUNT = new Iban("DE07200200200123456789");
    private static final Bic OTHER_BIC = new Bic("OTHRDEFFXXX");

    private final MutableClock clock = new MutableClock(START);
    private final List<String> sent = new ArrayList<>();
    private int gatewayStatus = 202;
    private boolean gatewayUnreachable;
    private final EnvelopeCodec envelopes = new EnvelopeCodec();
    private final CsmSimulation simulation = new CsmSimulation(envelopes,
            new OutgoingScheme(BENEFICIARY_DELAY, Duration.ofSeconds(20)), new IncomingScheme(ANSWER_TIMEOUT),
            new Pacs002Builder(), this::gateway, clock, new UlidGenerator(clock, new Random(7)), CSM_BIC,
            new InMemoryBankDirectory("10010010", OUR_BIC, List.of(
                    new BankEntry("10010010", OUR_BIC, true, Optional.empty()),
                    new BankEntry("20020020", PEER_BIC, true, Optional.empty()))));

    @Test
    void anIncomingPaymentGoesToTheGatewayAsAPacs008FromTheDebtorsBankToOurs() {
        IncomingPaymentSent result = simulation.sendIncomingPayment(payment("12.34", null, null));

        assertThat(result.gatewayStatus()).isEqualTo(202);
        assertThat(sent).hasSize(1);
        FIToFICustomerCreditTransferV08 transfer = creditTransfer(sent.getFirst());
        var transaction = transfer.getCdtTrfTxInf().getFirst();
        assertThat(transaction.getPmtId().getTxId()).isEqualTo(result.txId().value());
        assertThat(transaction.getDbtrAgt().getFinInstnId().getBICFI())
                .as("the debtor's bank from the directory").isEqualTo(PEER_BIC.value());
        assertThat(transaction.getCdtrAgt().getFinInstnId().getBICFI()).isEqualTo(OUR_BIC.value());
        assertThat(transaction.getCdtrAcct().getId().getIBAN()).isEqualTo(OUR_ACCOUNT.value());
        assertThat(transaction.getDbtr().getNm()).isEqualTo("Hans Mueller");
        assertThat(transaction.getDbtrAcct().getId().getIBAN()).isEqualTo(PEER_ACCOUNT.value());
        assertThat(transaction.getIntrBkSttlmAmt().getValue()).isEqualByComparingTo("12.34");
        assertThat(transaction.getAccptncDtTm().toGregorianCalendar().toInstant()).isEqualTo(START);
        assertThat(simulation.journal(result.txId()))
                .extracting(JournalEntry::kind, JournalEntry::messageType, JournalEntry::envelope)
                .containsExactly(tuple(Kind.SENT, CsmMessageType.PACS_008.version(), sent.getFirst()));
    }

    @Test
    void anAcceptedAnswerInTimeIsSettledAndConfirmedWithASettlementReport() {
        TxId txId = simulation.sendIncomingPayment(payment("12.34", null, null)).txId();
        String incoming = sent.getFirst();

        clock.advance(ANSWER_TIMEOUT.minusMillis(1));
        simulation.receive(answer("ANS-1", incoming, Status.ACCEPTED, clock.instant()));
        simulation.tick();

        assertThat(simulation.settlements(Instant.EPOCH)).containsExactly(new Settlement(txId, PEER_BIC,
                Direction.INCOMING, 1234, START.plus(ANSWER_TIMEOUT).minusMillis(1)));
        assertThat(sent).hasSize(2);
        FIToFIPaymentStatusReportV10 confirmation = statusReport(sent.get(1));
        var transaction = confirmation.getTxInfAndSts().getFirst();
        assertThat(transaction.getTxSts()).isEqualTo("ACSC");
        assertThat(transaction.getOrgnlTxId()).isEqualTo(txId.value());
        assertThat(transaction.getOrgnlTxRef().getDbtrAgt().getFinInstnId().getBICFI()).isEqualTo(PEER_BIC.value());
        assertThat(transaction.getInstgAgt().getFinInstnId().getBICFI()).isEqualTo(CSM_BIC.value());
        assertThat(transaction.getInstdAgt().getFinInstnId().getBICFI()).isEqualTo(OUR_BIC.value());
        assertThat(confirmation.getOrgnlGrpInfAndSts().getFirst().getOrgnlMsgId())
                .isEqualTo(creditTransfer(incoming).getGrpHdr().getMsgId());
        assertThat(confirmation.getOrgnlGrpInfAndSts().getFirst().getOrgnlMsgNmId())
                .isEqualTo(CsmMessageType.PACS_008.version());
        assertThat(simulation.journal(txId)).extracting(JournalEntry::kind, JournalEntry::messageType)
                .containsExactly(
                        tuple(Kind.SENT, CsmMessageType.PACS_008.version()),
                        tuple(Kind.RECEIVED, CsmMessageType.PACS_002.version()),
                        tuple(Kind.SENT, CsmMessageType.PACS_002.version()));
    }

    @Test
    void aRejectedAnswerIsRecordedAndNothingElseIsSent() {
        TxId txId = simulation.sendIncomingPayment(payment("12.34", null, null)).txId();

        simulation.receive(answer("ANS-1", sent.getFirst(), Status.rejected(ReasonCode.AC04), clock.instant()));
        simulation.tick();
        clock.advance(Duration.ofMinutes(1));
        simulation.tick();

        assertThat(sent).hasSize(1);
        assertThat(simulation.settlements(Instant.EPOCH)).isEmpty();
        assertThat(simulation.journal(txId)).extracting(JournalEntry::kind).containsExactly(Kind.SENT, Kind.RECEIVED);
    }

    @Test
    void noAnswerTimesOutAndNothingIsSent() {
        simulation.sendIncomingPayment(payment("12.34", null, null));

        clock.advance(Duration.ofMinutes(1));
        simulation.tick();

        assertThat(sent).hasSize(1);
        assertThat(simulation.settlements(Instant.EPOCH)).isEmpty();
    }

    @Test
    void anAcceptedAnswerAtOrAfterTheAnswerTimeoutIsRecordedAndNeverSettled() {
        TxId first = simulation.sendIncomingPayment(payment("12.34", null, null)).txId();
        String firstIncoming = sent.getLast();
        clock.advance(ANSWER_TIMEOUT);
        simulation.receive(answer("ANS-1", firstIncoming, Status.ACCEPTED, clock.instant()));
        simulation.tick();

        simulation.sendIncomingPayment(payment("1.00", null, null));
        String secondIncoming = sent.getLast();
        clock.advance(ANSWER_TIMEOUT.plusSeconds(5));
        simulation.tick();
        simulation.receive(answer("ANS-2", secondIncoming, Status.ACCEPTED, clock.instant()));
        simulation.tick();

        assertThat(simulation.settlements(Instant.EPOCH)).isEmpty();
        assertThat(sent).as("two pacs.008, no confirmation").hasSize(2);
        assertThat(simulation.journal(first)).extracting(JournalEntry::kind).containsExactly(Kind.SENT, Kind.RECEIVED);
    }

    @Test
    void aSecondAnswerChangesNothing() {
        simulation.sendIncomingPayment(payment("12.34", null, null));
        String incoming = sent.getFirst();

        simulation.receive(answer("ANS-1", incoming, Status.ACCEPTED, clock.instant()));
        simulation.tick();
        simulation.receive(answer("ANS-2", incoming, Status.rejected(ReasonCode.AC04), clock.instant()));
        simulation.receive(answer("ANS-3", incoming, Status.ACCEPTED, clock.instant()));
        simulation.tick();

        assertThat(simulation.settlements(Instant.EPOCH)).hasSize(1);
        assertThat(sent).hasSize(2);
    }

    @Test
    void SIM_AC_07_anExplicitTxIdAndDebtorAgentAreCarriedAndTheSameTxIdFromTwoBanksIsTwoTransactions() {
        TxId txId = new TxId("PEER-TX-1");
        simulation.sendIncomingPayment(payment("10.00", txId, PEER_BIC));
        simulation.sendIncomingPayment(payment("20.00", txId, OTHER_BIC));

        assertThat(sent).extracting(xml -> creditTransfer(xml).getCdtTrfTxInf().getFirst())
                .extracting(t -> t.getPmtId().getTxId(), t -> t.getDbtrAgt().getFinInstnId().getBICFI())
                .containsExactly(tuple("PEER-TX-1", PEER_BIC.value()), tuple("PEER-TX-1", OTHER_BIC.value()));

        simulation.receive(answer("ANS-1", sent.get(1), Status.ACCEPTED, clock.instant()));
        simulation.receive(answer("ANS-2", sent.get(0), Status.rejected(ReasonCode.AC04), clock.instant()));
        simulation.tick();

        assertThat(simulation.settlements(Instant.EPOCH)).containsExactly(
                new Settlement(txId, OTHER_BIC, Direction.INCOMING, 2000, START));
        assertThat(sent).hasSize(3);
        assertThat(statusReport(sent.get(2)).getTxInfAndSts().getFirst().getOrgnlTxRef().getDbtrAgt()
                .getFinInstnId().getBICFI()).isEqualTo(OTHER_BIC.value());
    }

    @Test
    void SIM_AC_06_theSettlementLogHoldsExactlyTheSettledTransactionsInBothDirections() {
        simulation.setScenario(new Iban("DE89370400440532013000"), Scenario.parse("REJECT:AC04"));
        simulation.receive(pacs008("MSG-1", "OUT-1", "1.00", START));
        simulation.receive(pacs008("MSG-2", "OUT-2", "2.00", START, new Iban("DE89370400440532013000")));
        TxId accepted = simulation.sendIncomingPayment(payment("3.00", null, null)).txId();
        String acceptedIncoming = sent.getLast();
        simulation.sendIncomingPayment(payment("4.00", null, null));
        String rejectedIncoming = sent.getLast();
        simulation.sendIncomingPayment(payment("5.00", null, null));
        simulation.receive(answer("ANS-1", acceptedIncoming, Status.ACCEPTED, clock.instant()));
        simulation.receive(answer("ANS-2", rejectedIncoming, Status.rejected(ReasonCode.AC04), clock.instant()));
        simulation.tick();
        clock.advance(Duration.ofMinutes(1));
        simulation.tick();

        assertThat(simulation.settlements(Instant.EPOCH))
                .extracting(Settlement::txId, Settlement::direction, Settlement::amountMinor)
                .containsExactlyInAnyOrder(
                        tuple(new TxId("OUT-1"), Direction.OUTGOING, 100L),
                        tuple(accepted, Direction.INCOMING, 300L));
    }

    @Test
    void SIM_AC_02_everyMessageOfTheIncomingFlowIsSchemaValid() {
        simulation.sendIncomingPayment(payment("12.34", null, null));
        simulation.sendIncomingPayment(payment("1.00", new TxId("PEER-TX-1"), OTHER_BIC));
        simulation.receive(answer("ANS-1", sent.getFirst(), Status.ACCEPTED, clock.instant()));
        simulation.tick();

        assertThat(sent).hasSize(3).allSatisfy(xml -> assertThat(envelopes.split(xml)).isInstanceOf(Accepted.class));
    }

    @Test
    void aDebtorIbanOfNoBankInTheDirectoryNeedsAnExplicitDebtorAgent() {
        var unknownBank = new IncomingPayment(OUR_ACCOUNT, Money.parse("1.00"), new PartyName("Hans Mueller"),
                new Iban("DE89370400440532013000"), null, null);

        assertThatThrownBy(() -> simulation.sendIncomingPayment(unknownBank))
                .isInstanceOfSatisfying(InvalidIncomingPaymentException.class,
                        e -> assertThat(e.field()).isEqualTo("debtorAgentBic"));
        assertThat(sent).isEmpty();
    }

    @Test
    void theSameTxIdFromTheSameBankTwiceIsRefused() {
        simulation.sendIncomingPayment(payment("1.00", new TxId("PEER-TX-1"), PEER_BIC));

        assertThatThrownBy(() -> simulation.sendIncomingPayment(payment("1.00", new TxId("PEER-TX-1"), PEER_BIC)))
                .isInstanceOfSatisfying(InvalidIncomingPaymentException.class,
                        e -> assertThat(e.field()).isEqualTo("txId"));
        assertThat(sent).hasSize(1);
    }

    @Test
    void aPacs008TheGatewayDoesNotTakeIsReportedNotJournalledAndNeverSettled() {
        gatewayStatus = 503;

        IncomingPaymentSent result = simulation.sendIncomingPayment(payment("1.00", null, null));
        simulation.receive(answer("ANS-1", sent.getFirst(), Status.ACCEPTED, clock.instant()));
        simulation.tick();

        assertThat(result.gatewayStatus()).isEqualTo(503);
        assertThat(simulation.journal(result.txId())).extracting(JournalEntry::kind).containsExactly(Kind.RECEIVED);
        assertThat(simulation.settlements(Instant.EPOCH)).isEmpty();
    }

    @Test
    void aGatewayThatGivesNoAnswerIsUnreachableButMayHaveTakenThePayment() {
        gatewayUnreachable = true;

        assertThatThrownBy(() -> simulation.sendIncomingPayment(payment("1.00", new TxId("PEER-TX-1"), PEER_BIC)))
                .isInstanceOf(GatewayUnreachableException.class);
        String incoming = simulation.journal(new TxId("PEER-TX-1")).getFirst().envelope();
        simulation.receive(answer("ANS-1", incoming, Status.ACCEPTED, clock.instant()));
        simulation.tick();

        assertThat(simulation.settlements(Instant.EPOCH)).as("the gateway answered it after all").hasSize(1);
    }

    @Test
    void anAnswerWithoutADebtorAgentIsMatchedByItsOriginalMsgId() {
        TxId txId = simulation.sendIncomingPayment(payment("1.00", null, null)).txId();
        String withoutDebtorAgent = answer("ANS-1", sent.getFirst(), Status.ACCEPTED, clock.instant())
                .replaceAll("(?s)<OrgnlTxRef>.*?</OrgnlTxRef>", "");

        simulation.receive(withoutDebtorAgent);
        simulation.tick();

        assertThat(simulation.settlements(Instant.EPOCH)).extracting(Settlement::txId).containsExactly(txId);
    }

    @Test
    void resetForgetsIncomingPaymentsAwaitingAnAnswer() {
        simulation.sendIncomingPayment(payment("1.00", new TxId("PEER-TX-1"), PEER_BIC));
        String incoming = sent.getFirst();

        simulation.reset();
        simulation.receive(answer("ANS-1", incoming, Status.ACCEPTED, clock.instant()));
        simulation.tick();

        assertThat(simulation.settlements(Instant.EPOCH)).isEmpty();
        assertThat(sent).hasSize(1);
        simulation.sendIncomingPayment(payment("1.00", new TxId("PEER-TX-1"), PEER_BIC));
        assertThat(sent).as("the TxId is free again").hasSize(2);
    }

    private static IncomingPayment payment(String amount, TxId txId, Bic debtorAgent) {
        return new IncomingPayment(OUR_ACCOUNT, Money.parse(amount), new PartyName("Hans Mueller"), PEER_ACCOUNT,
                txId, debtorAgent);
    }

    private int gateway(String envelope) {
        if (gatewayUnreachable) {
            throw new IllegalStateException("connection refused");
        }
        sent.add(envelope);
        return gatewayStatus;
    }

    private FIToFICustomerCreditTransferV08 creditTransfer(String xml) {
        CsmEnvelope envelope = ((Accepted) envelopes.split(xml)).envelope();
        assertThat(envelope.messageType()).isEqualTo(CsmMessageType.PACS_008);
        return ((com.tpe.iso20022model.pacs008v08.Document) envelope.document()).getFIToFICstmrCdtTrf();
    }

    private FIToFIPaymentStatusReportV10 statusReport(String xml) {
        CsmEnvelope envelope = ((Accepted) envelopes.split(xml)).envelope();
        assertThat(envelope.messageType()).isEqualTo(CsmMessageType.PACS_002);
        return ((com.tpe.iso20022model.pacs002v10.Document) envelope.document()).getFIToFIPmtStsRpt();
    }
}
