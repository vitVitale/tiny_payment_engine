package com.tpe.paymentorchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.paymentorchestrator.SagaProbe.Row;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code SEND} after {@code send_by} (specs/11-payment-orchestrator.md §6.2, UC-04 5a): the hold's answer is held
 * until the clock passed {@code send_by}, so {@code SEND} runs late. The lease outlasts the hold, so the sweeper does
 * not take {@code CREATE_HOLD} over meanwhile.
 */
@TestPropertySource(properties = "tpe.runner.lease=60s")
class SendByIT extends OrchestratorStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration AWAIT = Duration.ofSeconds(15);

    @Autowired
    JdbcClient jdbc;

    @Test
    void ORC_AC_06_aSendAfterSendByWritesNoCommandRejectsAb03AndReleasesTheHold() {
        SagaProbe probe = new SagaProbe(jdbc, CLOCK, RISK, LEDGER);
        riskAnswers("allow");
        ledgerAnswers("create-hold-created", "release-released");
        String payee = Ibans.peer();
        ActionInterference.holdAfter(PendingAction.CREATE_HOLD, payee);

        UUID paymentId = submit(payee);
        await().atMost(AWAIT).until(() -> ActionInterference.held() >= 1);
        // send_by is acceptedAt + 10 s, the deadline 20 s
        CLOCK.advance(Duration.ofSeconds(11));
        ActionInterference.releaseHeld();

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId)).isEqualTo(
                new Row("REJECTED", "AB03", null, true)));
        assertThat(probe.csmCommands(probe.txId(paymentId))).isEmpty();
        assertThat(probe.transitions(paymentId)).endsWith("RISK_CHECKED|FUNDS_RESERVED|HOLD_CREATED",
                "FUNDS_RESERVED|REJECTED|SEND_BY_PASSED");
        assertThat(probe.releaseCalls(paymentId)).hasSize(1);
        assertThat(probe.finalEvents(paymentId)).containsExactly("PaymentRejected|AB03");
    }

    private UUID submit(String payee) {
        Map<String, Object> request = PaymentCommandIT.request(Ibans.own(), payee);
        String body = http().post().uri("/v1/payments").contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(request)).retrieve().body(String.class);
        return UUID.fromString(JSON.readTree(body).get("paymentId").asString());
    }
}
