package com.tpe.csmsimulator;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.tpe.csmsimulator.CreditTransfers.CREDITOR;
import static com.tpe.csmsimulator.CreditTransfers.pacs008;
import static com.tpe.csmsimulator.CreditTransfers.pacs028;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.http.Fault;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.EnvelopeCodec.Accepted;
import com.tpe.iso20022model.pacs002v10.Document;
import com.tpe.iso20022model.pacs002v10.FIToFIPaymentStatusReportV10;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The scenarios that keep the outcome from us and the investigations that recover it, set up through the control API
 * only (specs/17-csm-simulator.md §3.2, §3.5).
 */
class InvestigationIT extends SimulatorStack {

    /** The timeline runs every {@code tpe.sim.tick} (5 ms); work that is due is done well within this. */
    private static final Duration DUE_WORK = Duration.ofSeconds(2);
    /** Twenty ticks of the timeline at a frozen clock: long enough for work that is not due to show if it ran. */
    private static final Duration NOT_DUE = Duration.ofMillis(100);

    private final EnvelopeCodec envelopes = new EnvelopeCodec();

    @Test
    void SIM_AC_05_lateStatusSettlesOnceAndReportsOnceAtAcceptanceTimePlusItsDelay() throws Exception {
        scenario("LATE_STATUS:2000");
        transmit(pacs008("MSG-1", "TX-1", "25.00", START));
        CLOCK.advance(Duration.ofMillis(300));
        await().atMost(DUE_WORK).until(() -> get("/sim/v1/settlements").body().contains("TX-1"));

        CLOCK.set(START.plusSeconds(1));
        transmit(pacs028("REQ-1", "MSG-1", "TX-1", CLOCK.instant()));
        FIToFIPaymentStatusReportV10 investigated = awaitReport(1);
        assertThat(investigated.getOrgnlGrpInfAndSts().getFirst().getOrgnlMsgId()).isEqualTo("REQ-1");
        assertThat(investigated.getTxInfAndSts().getFirst().getTxSts()).isEqualTo("ACSC");

        CLOCK.set(START.plusMillis(1999));
        await().during(NOT_DUE).atMost(DUE_WORK).until(() -> sentToGateway().size() == 1);
        CLOCK.set(START.plusSeconds(2));
        FIToFIPaymentStatusReportV10 late = awaitReport(2);
        assertThat(late.getOrgnlGrpInfAndSts().getFirst().getOrgnlMsgId()).isEqualTo("MSG-1");
        assertThat(late.getTxInfAndSts().getFirst().getTxSts()).isEqualTo("ACSC");
        assertThat(get("/sim/v1/received?txId=TX-1").body()).contains("\"at\":\"2026-11-15T10:00:02Z\"");

        CLOCK.advance(Duration.ofMinutes(1));
        await().during(NOT_DUE).atMost(DUE_WORK).until(() -> sentToGateway().size() == 2);
        assertThat(get("/sim/v1/settlements").body().split("\"txId\"")).as("settled once").hasSize(2);
    }

    @Test
    void silentSendsNoReportAndAnInvestigationIsAnsweredSettled() throws Exception {
        scenario("SILENT");
        transmit(pacs008("MSG-1", "TX-1", "25.00", START));
        CLOCK.advance(Duration.ofSeconds(30));
        await().atMost(DUE_WORK).until(() -> get("/sim/v1/settlements").body().contains("TX-1"));
        await().during(NOT_DUE).atMost(DUE_WORK).until(() -> sentToGateway().isEmpty());

        transmit(pacs028("REQ-1", "MSG-1", "TX-1", CLOCK.instant()));

        assertThat(awaitReport(1).getTxInfAndSts().getFirst().getTxSts()).isEqualTo("ACSC");
    }

    @Test
    void lostIsNeitherSettledNorAnsweredAndAnInvestigationIsAnsweredUnknownTx() throws Exception {
        scenario("LOST");
        assertThat(transmit(pacs008("MSG-1", "TX-1", "25.00", START)).statusCode()).isEqualTo(202);
        CLOCK.advance(Duration.ofSeconds(30));
        await().during(NOT_DUE).atMost(DUE_WORK).until(() -> sentToGateway().isEmpty());

        transmit(pacs028("REQ-1", "MSG-1", "TX-1", CLOCK.instant()));

        var answer = awaitReport(1).getTxInfAndSts().getFirst();
        assertThat(answer.getTxSts()).isEqualTo("RJCT");
        assertThat(answer.getStsRsnInf().getFirst().getRsn().getPrtry()).isEqualTo("UNKNOWN_TX");
        assertThat(get("/sim/v1/settlements").body()).isEqualTo("[]");
    }

    @Test
    void investigationSilentIgnoresInvestigationsUntilTheScenarioIsCleared() throws Exception {
        scenario("SILENT+INVESTIGATION_SILENT");
        transmit(pacs008("MSG-1", "TX-1", "25.00", START));
        CLOCK.advance(Duration.ofMillis(300));
        await().atMost(DUE_WORK).until(() -> get("/sim/v1/settlements").body().contains("TX-1"));

        transmit(pacs028("REQ-1", "MSG-1", "TX-1", CLOCK.instant()));
        await().during(NOT_DUE).atMost(DUE_WORK).until(() -> sentToGateway().isEmpty());

        scenario("SILENT");
        transmit(pacs028("REQ-2", "MSG-1", "TX-1", CLOCK.instant()));

        FIToFIPaymentStatusReportV10 answer = awaitReport(1);
        assertThat(answer.getOrgnlGrpInfAndSts().getFirst().getOrgnlMsgId()).isEqualTo("REQ-2");
        assertThat(answer.getTxInfAndSts().getFirst().getTxSts()).isEqualTo("ACSC");
    }

    @Test
    void theAmountShortcutPlaysSilent() throws Exception {
        transmit(pacs008("MSG-1", "TX-1", "25.94", START));
        CLOCK.advance(Duration.ofSeconds(30));

        await().atMost(DUE_WORK).until(() -> get("/sim/v1/settlements").body().contains("TX-1"));
        await().during(NOT_DUE).atMost(DUE_WORK).until(() -> sentToGateway().isEmpty());
    }

    @Test
    void aRawMessageGoesToTheGatewayAsItIsAndItsStatusIsReported() throws Exception {
        String contradicting = "<not-even-xml";

        var response = send("POST", "/sim/v1/incoming-payments/raw", "application/xml", contradicting);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"gatewayStatus\":202}");
        assertThat(sentToGateway()).containsExactly(contradicting);
    }

    @Test
    void aRawMessageTheGatewayRefusesIsReportedWithTheGatewaysStatus() throws Exception {
        GATEWAY.stubFor(post("/csm/v1/inbound").willReturn(aResponse().withStatus(400)));

        var response = send("POST", "/sim/v1/incoming-payments/raw", "application/xml",
                pacs008("MSG-1", "TX-1", "25.00", START));

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"gatewayStatus\":400}");
        assertThat(get("/sim/v1/received?txId=TX-1").body()).isEqualTo("[]");
    }

    @Test
    void aRawMessageTheGatewayGivesNoAnswerToIs502() throws Exception {
        GATEWAY.stubFor(post("/csm/v1/inbound").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        var response = send("POST", "/sim/v1/incoming-payments/raw", "application/xml", "<x/>");

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("\"code\":\"GATEWAY_UNREACHABLE\"");
    }

    private void scenario(String scenario) throws Exception {
        assertThat(send("PUT", "/sim/v1/scenarios/" + CREDITOR.value(), "application/json",
                "{\"scenario\": \"" + scenario + "\"}").statusCode()).isEqualTo(204);
    }

    /** Waits for the {@code count}th message to the gateway and reads it as a status report. */
    private FIToFIPaymentStatusReportV10 awaitReport(int count) {
        await().atMost(DUE_WORK).until(() -> sentToGateway().size() >= count);
        assertThat(sentToGateway()).hasSize(count);
        var envelope = ((Accepted) envelopes.split(sentToGateway().get(count - 1))).envelope();
        return ((Document) envelope.document()).getFIToFIPmtStsRpt();
    }
}
