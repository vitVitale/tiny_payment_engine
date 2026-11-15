package com.tpe.csmsimulator.application;

import static com.tpe.csmsimulator.CreditTransfers.CREDITOR;
import static com.tpe.csmsimulator.CreditTransfers.OUR_BIC;
import static com.tpe.csmsimulator.CreditTransfers.pacs008;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.csmsimulator.application.CsmSimulation.Received;
import com.tpe.csmsimulator.application.CsmSimulation.Refused;
import com.tpe.csmsimulator.application.JournalEntry.Kind;
import com.tpe.csmsimulator.domain.scenarios.Scenario;
import com.tpe.csmsimulator.domain.scheme.OutgoingScheme;
import com.tpe.csmsimulator.domain.scheme.Settlement;
import com.tpe.csmsimulator.domain.scheme.Settlement.Direction;
import com.tpe.iso20022model.CsmEnvelope;
import com.tpe.iso20022model.CsmMessageType;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.EnvelopeCodec.Accepted;
import com.tpe.iso20022model.Pacs002Builder;
import com.tpe.iso20022model.pacs002v10.PaymentTransaction110;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.MutableClock;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.UlidGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CsmSimulationTest {

    private static final Instant START = Instant.parse("2026-11-15T10:00:00.000Z");
    private static final Bic CSM_BIC = new Bic("TIPSXXXXXXX");
    private static final Duration BENEFICIARY_DELAY = Duration.ofMillis(300);
    private static final Duration TIMEOUT_DEADLINE = Duration.ofSeconds(20);

    private final MutableClock clock = new MutableClock(START);
    private final List<String> sent = new ArrayList<>();
    private boolean gatewayDown;
    private final EnvelopeCodec envelopes = new EnvelopeCodec();
    private final CsmSimulation simulation = new CsmSimulation(envelopes,
            new OutgoingScheme(BENEFICIARY_DELAY, TIMEOUT_DEADLINE), new Pacs002Builder(), this::gateway, clock,
            new UlidGenerator(clock, new Random(7)), CSM_BIC);

    @Test
    void anAcceptedTransferIsSettledAndAnsweredSettledAfterTheBeneficiaryDelay() {
        assertThat(simulation.receive(pacs008("MSG-1", "TX-1", "10.00", START))).isInstanceOf(Received.class);
        simulation.tick();

        clock.advance(BENEFICIARY_DELAY.minusMillis(1));
        simulation.tick();
        assertThat(sent).isEmpty();
        assertThat(simulation.settlements(Instant.EPOCH)).isEmpty();

        clock.advance(Duration.ofMillis(1));
        simulation.tick();

        PaymentTransaction110 answer = onlyStatusReport();
        assertThat(answer.getTxSts()).isEqualTo("ACSC");
        assertThat(answer.getOrgnlTxId()).isEqualTo("TX-1");
        assertThat(answer.getOrgnlTxRef().getDbtrAgt().getFinInstnId().getBICFI()).isEqualTo(OUR_BIC.value());
        assertThat(answer.getInstgAgt().getFinInstnId().getBICFI()).isEqualTo(CSM_BIC.value());
        assertThat(simulation.settlements(Instant.EPOCH)).containsExactly(
                new Settlement(new TxId("TX-1"), OUR_BIC, Direction.OUTGOING, 1000, START.plus(BENEFICIARY_DELAY)));
    }

    @Test
    void aRejectScenarioAnswersRejectedWithItsReasonAndSettlesNothing() {
        simulation.setScenario(CREDITOR, Scenario.parse("REJECT:AC04"));

        simulation.receive(pacs008("MSG-1", "TX-1", "10.00", START));
        simulation.tick();
        simulation.tick();

        PaymentTransaction110 answer = onlyStatusReport();
        assertThat(answer.getTxSts()).isEqualTo("RJCT");
        assertThat(answer.getStsRsnInf().getFirst().getRsn().getCd()).isEqualTo("AC04");
        assertThat(simulation.settlements(Instant.EPOCH)).isEmpty();
    }

    @Test
    void aTimeoutScenarioAnswersAb05AtTheTimeOutDeadlineAndSettlesNothing() {
        simulation.setScenario(CREDITOR, Scenario.parse("TIMEOUT"));
        Instant acceptedAt = START.minusSeconds(1);

        simulation.receive(pacs008("MSG-1", "TX-1", "10.00", acceptedAt));
        simulation.tick();
        clock.set(acceptedAt.plus(TIMEOUT_DEADLINE).minusMillis(1));
        simulation.tick();
        assertThat(sent).isEmpty();

        clock.set(acceptedAt.plus(TIMEOUT_DEADLINE));
        simulation.tick();

        assertThat(onlyStatusReport().getStsRsnInf().getFirst().getRsn().getCd()).isEqualTo("AB05");
        assertThat(simulation.settlements(Instant.EPOCH)).isEmpty();
    }

    @Test
    void SIM_AC_03_aTransferRetriedWithTheSameMsgIdIsSettledOnce() {
        String transfer = pacs008("MSG-1", "TX-1", "10.00", START);

        simulation.receive(transfer);
        simulation.receive(transfer);
        simulation.tick();
        clock.advance(BENEFICIARY_DELAY);
        simulation.tick();
        simulation.receive(transfer);
        clock.advance(BENEFICIARY_DELAY);
        simulation.tick();

        assertThat(simulation.settlements(Instant.EPOCH)).hasSize(1);
        assertThat(sent).hasSize(1);
        assertThat(simulation.journal(new TxId("TX-1")))
                .extracting(JournalEntry::kind, JournalEntry::duplicate)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(Kind.RECEIVED, false),
                        org.assertj.core.groups.Tuple.tuple(Kind.RECEIVED, true),
                        org.assertj.core.groups.Tuple.tuple(Kind.SENT, false),
                        org.assertj.core.groups.Tuple.tuple(Kind.RECEIVED, true));
    }

    @Test
    void SIM_AC_04_aTransferArrivingAfterTheTimeOutDeadlineIsNeverSettled() {
        Instant acceptedAt = START.minus(TIMEOUT_DEADLINE).minusMillis(1);

        simulation.receive(pacs008("MSG-1", "TX-1", "10.00", acceptedAt));
        simulation.tick();
        clock.advance(Duration.ofMinutes(1));
        simulation.tick();

        PaymentTransaction110 answer = onlyStatusReport();
        assertThat(answer.getTxSts()).isEqualTo("RJCT");
        assertThat(answer.getStsRsnInf().getFirst().getRsn().getCd()).isEqualTo("AB05");
        assertThat(simulation.settlements(Instant.EPOCH)).isEmpty();
    }

    @Test
    void theSettlementLogHoldsExactlyTheSettledOutgoingTransfers() {
        Iban rejecting = new Iban("DE89370400440532013000");
        Iban timingOut = new Iban("DE02120300000000202051");
        simulation.setScenario(rejecting, Scenario.parse("REJECT:AC01"));
        simulation.setScenario(timingOut, Scenario.parse("TIMEOUT"));

        simulation.receive(pacs008("MSG-1", "TX-SETTLED", "10.00", START));
        simulation.receive(pacs008("MSG-2", "TX-REJECTED", "10.00", START, rejecting));
        simulation.receive(pacs008("MSG-3", "TX-TIMEOUT", "10.00", START, timingOut));
        simulation.receive(pacs008("MSG-4", "TX-LATE", "10.00", START.minusSeconds(21)));
        simulation.tick();
        clock.advance(Duration.ofSeconds(30));
        simulation.tick();

        assertThat(sent).hasSize(4);
        assertThat(simulation.settlements(Instant.EPOCH)).extracting(Settlement::txId)
                .containsExactly(new TxId("TX-SETTLED"));
    }

    @Test
    void settlementsSinceAnInstantLeaveOutEarlierOnes() {
        simulation.receive(pacs008("MSG-1", "TX-1", "10.00", START));
        simulation.tick();
        clock.advance(BENEFICIARY_DELAY);
        simulation.tick();
        Instant firstSettled = clock.instant();
        clock.advance(Duration.ofSeconds(1));
        simulation.receive(pacs008("MSG-2", "TX-2", "10.00", clock.instant()));
        simulation.tick();
        clock.advance(BENEFICIARY_DELAY);
        simulation.tick();

        assertThat(simulation.settlements(firstSettled)).extracting(Settlement::txId)
                .containsExactly(new TxId("TX-1"), new TxId("TX-2"));
        assertThat(simulation.settlements(firstSettled.plusMillis(1))).extracting(Settlement::txId)
                .containsExactly(new TxId("TX-2"));
    }

    @Test
    void SIM_AC_02_everyStatusReportItSendsIsSchemaValid() {
        simulation.setScenario(new Iban("DE89370400440532013000"), Scenario.parse("REJECT:XY99"));

        simulation.receive(pacs008("MSG-1", "TX-1", "10.00", START));
        simulation.receive(pacs008("MSG-2", "TX-2", "10.00", START, new Iban("DE89370400440532013000")));
        simulation.tick();
        clock.advance(BENEFICIARY_DELAY);
        simulation.tick();

        assertThat(sent).hasSize(2).allSatisfy(xml -> assertThat(envelopes.split(xml)).isInstanceOf(Accepted.class));
    }

    @Test
    void SIM_AC_02_anInvalidEnvelopeFromTheGatewayIsRefusedAndNotRecorded() {
        String invalid = pacs008("MSG-1", "TX-1", "10.00", START).replace("<ChrgBr>SLEV</ChrgBr>", "");

        assertThat(simulation.receive(invalid)).isInstanceOfSatisfying(Refused.class,
                refused -> assertThat(refused.part()).isEqualTo(EnvelopeCodec.Part.DOCUMENT));
        simulation.tick();
        clock.advance(Duration.ofSeconds(1));
        simulation.tick();

        assertThat(simulation.journal(new TxId("TX-1"))).isEmpty();
        assertThat(sent).isEmpty();
    }

    @Test
    void aSlowTransportProcessesAMessageItsDelayLater() {
        simulation.setOutage(new Outage(Outage.Mode.SLOW, Duration.ofSeconds(2)));

        simulation.receive(pacs008("MSG-1", "TX-1", "10.00", START));
        clock.advance(Duration.ofSeconds(2).plus(BENEFICIARY_DELAY).minusMillis(1));
        simulation.tick();
        simulation.tick();
        assertThat(sent).isEmpty();

        clock.advance(Duration.ofMillis(1));
        simulation.tick();
        simulation.tick();
        assertThat(sent).hasSize(1);
    }

    @Test
    void resetDropsAllStateAndPendingWork() {
        simulation.setScenario(CREDITOR, Scenario.parse("TIMEOUT"));
        simulation.setOutage(new Outage(Outage.Mode.DOWN, Duration.ZERO));
        simulation.receive(pacs008("MSG-1", "TX-1", "10.00", START));
        simulation.tick();

        simulation.reset();
        clock.advance(Duration.ofMinutes(1));
        simulation.tick();

        assertThat(sent).isEmpty();
        assertThat(simulation.journal(new TxId("TX-1"))).isEmpty();
        assertThat(simulation.outage()).isEqualTo(Outage.NONE);
        simulation.receive(pacs008("MSG-1", "TX-1", "10.00", clock.instant()));
        simulation.tick();
        clock.advance(BENEFICIARY_DELAY);
        simulation.tick();
        assertThat(onlyStatusReport().getTxSts()).as("scenario and MsgId forgotten").isEqualTo("ACSC");
    }

    @Test
    void SIM_AC_02_aSchemaValidTransferItCannotReadIsRefusedAndNotRecorded() {
        String dollars = pacs008("MSG-1", "TX-1", "10.00", START).replace("Ccy=\"EUR\"", "Ccy=\"USD\"");

        assertThat(simulation.receive(dollars)).isInstanceOfSatisfying(Refused.class, refused -> assertThat(
                refused.violations()).extracting(v -> v.code()).containsExactly(CsmSimulation.UNREADABLE_TRANSFER));
        simulation.tick();

        assertThat(simulation.journal(new TxId("TX-1"))).isEmpty();
        assertThat(simulation.receive(pacs008("MSG-1", "TX-1", "10.00", START)))
                .as("a refused MsgId is not marked processed").isInstanceOf(Received.class);
    }

    @Test
    void aStatusReportTheGatewayDoesNotTakeIsNotJournalledAsSent() {
        gatewayDown = true;

        simulation.receive(pacs008("MSG-1", "TX-1", "10.00", START));
        simulation.tick();
        clock.advance(BENEFICIARY_DELAY);
        simulation.tick();

        assertThat(simulation.journal(new TxId("TX-1"))).extracting(JournalEntry::kind).containsExactly(Kind.RECEIVED);
        assertThat(simulation.settlements(Instant.EPOCH)).as("the CSM settled; only its report was lost").hasSize(1);
    }

    private void gateway(String envelope) {
        if (gatewayDown) {
            throw new IllegalStateException("503 from csm-gateway");
        }
        sent.add(envelope);
    }

    private PaymentTransaction110 onlyStatusReport() {
        assertThat(sent).hasSize(1);
        CsmEnvelope envelope = ((Accepted) envelopes.split(sent.getFirst())).envelope();
        assertThat(envelope.messageType()).isEqualTo(CsmMessageType.PACS_002);
        return ((com.tpe.iso20022model.pacs002v10.Document) envelope.document())
                .getFIToFIPmtStsRpt().getTxInfAndSts().getFirst();
    }
}
