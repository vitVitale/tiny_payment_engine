package com.tpe.csmsimulator;

import static com.tpe.csmsimulator.CreditTransfers.CREDITOR;
import static com.tpe.csmsimulator.CreditTransfers.pacs008;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.EnvelopeCodec.Accepted;
import com.tpe.iso20022model.pacs002v10.PaymentTransaction110;
import java.io.IOException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The CSM transport and the outgoing scheme seen from csm-gateway (specs/17-csm-simulator.md §3.1, §3.2). */
@ExtendWith(OutputCaptureExtension.class)
class CsmTransportIT extends SimulatorStack {

    /** The timeline runs every {@code tpe.sim.tick} (5 ms); work that is due is done well within this. */
    private static final Duration DUE_WORK = Duration.ofSeconds(2);
    /** Twenty ticks of the timeline at a frozen clock: long enough for work that is not due to show if it ran. */
    private static final Duration NOT_DUE = Duration.ofMillis(100);

    private final EnvelopeCodec envelopes = new EnvelopeCodec();

    @Test
    void aTransferIsAcceptedAt202AndAnsweredSettledAfterTheBeneficiaryDelay() throws Exception {
        assertThat(transmit(pacs008("MSG-1", "TX-1", "25.00", START)).statusCode()).isEqualTo(202);

        CLOCK.advance(Duration.ofMillis(300));

        PaymentTransaction110 answer = awaitOneAnswer();
        assertThat(answer.getTxSts()).isEqualTo("ACSC");
        assertThat(answer.getOrgnlTxId()).isEqualTo("TX-1");
        assertThat(get("/sim/v1/settlements").body())
                .contains("\"txId\":\"TX-1\"", "\"debtorAgentBic\":\"TPEBDEFFXXX\"", "\"direction\":\"OUTGOING\"",
                        "\"amountMinor\":2500", "\"settledAt\":\"2026-11-15T10:00:00.300Z\"");
    }

    @Test
    void aScenarioSetThroughTheControlApiDecidesTheAnswer() throws Exception {
        var scenario = send("PUT", "/sim/v1/scenarios/" + CREDITOR.value(), "application/json",
                "{\"scenario\": \"REJECT:AC04\"}");
        assertThat(scenario.statusCode()).isEqualTo(204);

        transmit(pacs008("MSG-1", "TX-1", "25.00", START));

        PaymentTransaction110 answer = awaitOneAnswer();
        assertThat(answer.getTxSts()).isEqualTo("RJCT");
        assertThat(answer.getStsRsnInf().getFirst().getRsn().getCd()).isEqualTo("AC04");
        assertThat(get("/sim/v1/settlements").body()).isEqualTo("[]");
    }

    @Test
    void SIM_AC_02_anInvalidEnvelopeIsRefusedWith400AndALoggedError(CapturedOutput output) throws Exception {
        String invalid = pacs008("MSG-1", "TX-1", "25.00", START).replace("<ChrgBr>SLEV</ChrgBr>", "");

        var response = transmit(invalid);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"INVALID_ENVELOPE\"",
                "\"errors\":[{\"field\":\"DOCUMENT\",\"code\":\"cvc-complex-type.2.4.a\"}]");
        assertThat(output.getAll()).contains("ERROR", "refused an invalid DOCUMENT from csm-gateway");
        assertThat(get("/sim/v1/received?txId=TX-1").body()).isEqualTo("[]");
    }

    @Test
    void anUnparsableEnvelopeIs400() throws Exception {
        var response = transmit("not xml");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("\"code\":\"INVALID_ENVELOPE\"", "\"field\":\"ENVELOPE\"");
    }

    @Test
    void SIM_AC_02_theStatusReportItSendsIsSchemaValid() throws Exception {
        transmit(pacs008("MSG-1", "TX-1", "25.00", START));
        CLOCK.advance(Duration.ofMillis(300));

        await().atMost(DUE_WORK).until(() -> sentToGateway().size() == 1);
        assertThat(envelopes.split(sentToGateway().getFirst())).isInstanceOf(Accepted.class);
    }

    @Test
    void SIM_AC_08_downAnswers503AndTheMessageIsNeitherReceivedNorSettled() throws Exception {
        outage("{\"mode\": \"DOWN\"}");

        assertThat(transmit(pacs008("MSG-1", "TX-1", "25.00", START)).statusCode()).isEqualTo(503);

        outage("{\"mode\": \"UP\"}");
        assertNothingReceivedOrSettled();
    }

    @Test
    void SIM_AC_08_resetClosesTheConnectionAndTheMessageIsNeitherReceivedNorSettled() throws Exception {
        outage("{\"mode\": \"RESET\"}");

        assertThatThrownBy(() -> transmit(pacs008("MSG-1", "TX-1", "25.00", START)))
                .isInstanceOf(IOException.class);

        outage("{\"mode\": \"UP\"}");
        assertNothingReceivedOrSettled();
    }

    @Test
    void afterAnOutageTheTransportServesAgain() throws Exception {
        outage("{\"mode\": \"DOWN\"}");
        transmit(pacs008("MSG-1", "TX-1", "25.00", START));
        outage("{\"mode\": \"UP\"}");

        assertThat(transmit(pacs008("MSG-1", "TX-1", "25.00", START)).statusCode()).isEqualTo(202);
        CLOCK.advance(Duration.ofMillis(300));
        assertThat(awaitOneAnswer().getTxSts()).as("the retry is not a duplicate").isEqualTo("ACSC");
    }

    @Test
    void slowProcessesTheMessageItsDelayLater() throws Exception {
        outage("{\"mode\": \"SLOW\", \"delayMs\": 2000}");

        assertThat(transmit(pacs008("MSG-1", "TX-1", "25.00", START)).statusCode()).isEqualTo(202);
        CLOCK.advance(Duration.ofMillis(2299));
        await().during(NOT_DUE).atMost(DUE_WORK).until(() -> sentToGateway().isEmpty());

        CLOCK.advance(Duration.ofMillis(1));
        assertThat(awaitOneAnswer().getTxSts()).isEqualTo("ACSC");
    }

    @Test
    void theJournalShowsWhatWasReceivedAndSentForATransaction() throws Exception {
        transmit(pacs008("MSG-1", "TX-1", "25.00", START));
        CLOCK.advance(Duration.ofMillis(300));
        awaitOneAnswer();

        String journal = get("/sim/v1/received?txId=TX-1").body();

        assertThat(journal).contains("\"kind\":\"RECEIVED\"", "\"messageType\":\"pacs.008.001.08\"",
                "\"msgId\":\"MSG-1\"", "\"at\":\"2026-11-15T10:00:00Z\"", "\"kind\":\"SENT\"",
                "\"messageType\":\"pacs.002.001.10\"", "\"at\":\"2026-11-15T10:00:00.300Z\"");
        assertThat(journal.indexOf("RECEIVED")).isLessThan(journal.indexOf("SENT"));
    }

    private void outage(String body) throws Exception {
        assertThat(send("POST", "/sim/v1/outage", "application/json", body).statusCode()).isEqualTo(204);
    }

    private void assertNothingReceivedOrSettled() throws Exception {
        CLOCK.advance(Duration.ofSeconds(1));
        await().during(NOT_DUE).atMost(DUE_WORK).until(() -> sentToGateway().isEmpty());
        assertThat(get("/sim/v1/received?txId=TX-1").body()).isEqualTo("[]");
        assertThat(get("/sim/v1/settlements").body()).isEqualTo("[]");
        assertThat(sentToGateway()).isEmpty();
    }

    private PaymentTransaction110 awaitOneAnswer() {
        await().atMost(DUE_WORK).until(() -> !sentToGateway().isEmpty());
        assertThat(sentToGateway()).hasSize(1);
        var envelope = ((Accepted) envelopes.split(sentToGateway().getFirst())).envelope();
        return ((com.tpe.iso20022model.pacs002v10.Document) envelope.document())
                .getFIToFIPmtStsRpt().getTxInfAndSts().getFirst();
    }
}
