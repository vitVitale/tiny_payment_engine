package com.tpe.csmgateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.tpe.csmgateway.Commands.sendCreditTransfer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.tpe.csmgateway.application.outgoing.OutgoingTransfers;
import com.tpe.paymentdomain.MsgId;
import com.tpe.paymentdomain.UlidGenerator;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Retries, the send-by guard before every attempt and lease takeover (specs/15-csm-gateway.md §6.1, §6.5, ADR-011).
 * The service's clock is moved by hand; the transmit worker polls in real time.
 */
class TransmitRetriesIT extends GatewayStack {

    /** Kafka delivery, the listener and the transmitter: well within this. */
    private static final Duration HANDLED = Duration.ofSeconds(15);
    /** Long enough for the worker to have polled many times. */
    private static final Duration QUIET = Duration.ofSeconds(1);
    private static final Duration LEASE = Duration.ofSeconds(2);
    private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

    @Autowired
    private OutgoingTransfers transfers;

    @Autowired
    private UlidGenerator ulids;

    @Test
    void GW_AC_03_anUnreachableCsmGetsTheSamePacs008UntilSendByAndNeverAfter() {
        CSM.stubFor(post("/csm/v1/messages").willReturn(aResponse().withStatus(503)));
        // sendBy = START + 9 s
        publishCommand("TX-AC03", sendCreditTransfer("TX-AC03", START.minusSeconds(1)), UUID.randomUUID());
        await().atMost(HANDLED).until(() -> sentToCsm().size() == 1);

        // the back-off is at most 1 s: each second of the service's time brings exactly one more attempt
        for (int second = 1; second <= 8; second++) {
            int attempts = second + 1;
            CLOCK.set(START.plusSeconds(second));
            await().atMost(HANDLED).until(() -> sentToCsm().size() == attempts);
        }
        CLOCK.set(START.plusSeconds(9));

        await().atMost(HANDLED).until(() -> "GAVE_UP".equals(transfer("TX-AC03").get("transmit_status")));
        CLOCK.set(START.plusSeconds(60));
        await().during(QUIET).atMost(HANDLED).until(() -> sentToCsm().size() == 9);
        assertThat(sentToCsm()).as("every attempt is the journalled pacs.008, so the same MsgId").containsOnly(
                jdbc.sql("SELECT xml FROM message_journal WHERE tx_id = 'TX-AC03'").query(String.class).single());
        assertThat(transfer("TX-AC03")).as("attempted, so the CSM may have it: still SENT")
                .containsEntry("send_status", "SENT").containsEntry("not_sent_reason", null)
                .containsEntry("transmit_attempts", 9);
        assertThat(events("TX-AC03")).isEmpty();
    }

    @Test
    void GW_AC_04_aPacs008NeverAttemptedBeforeSendByIsNotSent() {
        // as the gateway left it when it stopped right after the commit: journalled as sent, no attempt claimed
        Instant sendBy = START.plusSeconds(9);
        CLOCK.set(sendBy.plusSeconds(5));
        String msgId = MsgId.generate(ulids).value();
        UUID paymentId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO outgoing_transfers (tx_id, payment_id, uetr, msg_id, accepted_at, send_by, deadline_at,
                  send_status, journalled_at, transmit_status, transmit_attempts, next_transmit_at, trace_context)
                VALUES ('TX-AC04', :paymentId, :uetr, :msgId, :acceptedAt, :sendBy, :deadlineAt, 'SENT',
                  :journalledAt, 'PENDING', 0, :journalledAt, :traceContext)""")
                .param("paymentId", paymentId).param("uetr", UUID.randomUUID()).param("msgId", msgId)
                .param("traceContext", "00-" + TRACE_ID + "-00f067aa0ba902b7-01")
                .param("acceptedAt", Timestamp.from(START.minusSeconds(1))).param("sendBy", Timestamp.from(sendBy))
                .param("deadlineAt", Timestamp.from(START.plusSeconds(19))).param("journalledAt", Timestamp.from(START))
                .update();
        jdbc.sql("""
                INSERT INTO message_journal (direction, message_type, msg_id, tx_id, debtor_agent_bic, xml, valid, at)
                VALUES ('OUT', 'pacs.008.001.08', :msgId, 'TX-AC04', 'TPEBDEFFXXX', '<journalled/>', true, :at)""")
                .param("msgId", msgId).param("at", Timestamp.from(START)).update();

        await().atMost(HANDLED).until(() -> !events("TX-AC04").isEmpty());

        assertThat(events("TX-AC04")).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("CreditTransferNotSent");
            assertThat(event.payload()).contains("\"reasonCode\": \"AB03\"", "\"direction\": \"OUTGOING\"",
                    "\"paymentId\": \"" + paymentId + "\"", "\"debtorAgentBic\": \"TPEBDEFFXXX\"");
        });
        assertThat(transfer("TX-AC04")).containsEntry("send_status", "NOT_SENT")
                .containsEntry("not_sent_reason", "AB03").containsEntry("transmit_status", "GAVE_UP")
                .containsEntry("transmit_attempts", 0);
        await().during(QUIET).atMost(HANDLED).until(() -> sentToCsm().isEmpty());
        assertThat(events("TX-AC04")).as("given up once").hasSize(1);
        assertThat(jdbc.sql("SELECT headers::text FROM outbox WHERE message_key = 'TX-AC04'").query(String.class)
                .single()).as("the payment's trace (ADR-014)").contains("00-" + TRACE_ID + "-");
    }

    @Test
    void aConnectionResetIsRetriedAfterTheBackoff() {
        CSM.stubFor(post("/csm/v1/messages").inScenario("reset").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)).willSetStateTo("up"));
        CSM.stubFor(post("/csm/v1/messages").inScenario("reset").whenScenarioStateIs("up")
                .willReturn(aResponse().withStatus(202)));
        publishCommand("TX-RESET", sendCreditTransfer("TX-RESET", START.minusSeconds(1)), UUID.randomUUID());
        await().atMost(HANDLED).until(() -> Integer.valueOf(1).equals(transfer("TX-RESET").get("transmit_attempts")));

        // the first back-off is 200 ms
        CLOCK.set(START.plusMillis(199));
        await().during(QUIET).atMost(HANDLED).until(() -> sentToCsm().size() == 1);
        CLOCK.set(START.plusMillis(200));

        await().atMost(HANDLED).until(() -> "DELIVERED".equals(transfer("TX-RESET").get("transmit_status")));
        assertThat(sentToCsm()).hasSize(2).containsOnly(sentToCsm().getFirst());
        assertThat(transfer("TX-RESET")).containsEntry("transmit_attempts", 2);
    }

    @Test
    void aClaimWhoseInstanceDiedIsTakenOverAfterItsLeaseAndDeliveredOnce() {
        CSM.stubFor(post("/csm/v1/messages").willReturn(aResponse().withStatus(503)));
        // sendBy = START + 9 s; the first attempt fails, the next is due at START + 200 ms
        publishCommand("TX-LEASE", sendCreditTransfer("TX-LEASE", START.minusSeconds(1)), UUID.randomUUID());
        await().atMost(HANDLED).until(() -> sentToCsm().size() == 1);
        CSM.resetRequests();
        CSM.stubFor(post("/csm/v1/messages").willReturn(aResponse().withStatus(202)));
        MsgId msgId = new MsgId((String) transfer("TX-LEASE").get("msg_id"));

        // another instance claims the second attempt at START + 1 s and dies before its HTTP call
        Instant claimedAt = START.plusSeconds(1);
        assertThat(transfers.claimTransmission(msgId, claimedAt, LEASE)).hasValue(2);

        CLOCK.set(claimedAt.plus(LEASE).minusMillis(1));
        await().during(QUIET).atMost(HANDLED).until(() -> sentToCsm().isEmpty());
        CLOCK.set(claimedAt.plus(LEASE));

        await().atMost(HANDLED).until(() -> "DELIVERED".equals(transfer("TX-LEASE").get("transmit_status")));
        await().during(QUIET).atMost(HANDLED).until(() -> sentToCsm().size() == 1);
        assertThat(transfer("TX-LEASE")).containsEntry("send_status", "SENT").containsEntry("transmit_attempts", 3);
        assertThat(events("TX-LEASE")).isEmpty();
    }
}
