package com.tpe.paymentorchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.tpe.paymentorchestrator.SagaProbe.Row;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * Crash resume of the M2 actions (ADR-003, ADR-008; ORC-AC-13, whose parameter list M3 and M4 extend): the instance
 * dies after an action's remote call, before its result is applied; once the lease has expired a sweeper hands the
 * action over again. The payment ends as without the crash, the repeated call carries the same idempotency key, so
 * the ledger effect is the same, and there is one final event.
 */
class SagaResumeIT extends OrchestratorStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Duration AWAIT = Duration.ofSeconds(10);
    /** Past {@code tpe.runner.lease}, 5 s, and well before the hold give-up at 8 s. */
    private static final Duration PAST_LEASE = Duration.ofSeconds(6);

    @Autowired
    JdbcClient jdbc;

    private SagaProbe probe;

    @BeforeEach
    void probe() {
        probe = new SagaProbe(jdbc, CLOCK, RISK, LEDGER);
    }

    /**
     * An action to crash after, the ledger's answers, and how the payment ends.
     *
     * @param reason {@code null} for a settled payment
     */
    record Crash(PendingAction crashed, List<String> ledger, String state, String reason, String finalEvent) {

        @Override
        public String toString() {
            return "crash after " + crashed;
        }
    }

    static List<Crash> crashes() {
        List<String> settles = List.of("create-hold-created", "settle-on-us-settled");
        return List.of(
                new Crash(PendingAction.ASSESS_RISK, settles, "SETTLED", null, "PaymentSettled|"),
                new Crash(PendingAction.CREATE_HOLD, settles, "SETTLED", null, "PaymentSettled|"),
                new Crash(PendingAction.ASSESS_PAYEE_RISK, settles, "SETTLED", null, "PaymentSettled|"),
                new Crash(PendingAction.SETTLE_ON_US, settles, "SETTLED", null, "PaymentSettled|"),
                new Crash(PendingAction.RELEASE_HOLD,
                        List.of("create-hold-created", "settle-on-us-blocked-payee", "release-released"),
                        "REJECTED", "AC06", "PaymentRejected|AC06"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("crashes")
    void aCrashAfterTheRemoteCallResumesToTheSameOutcome(Crash crash) {
        PendingAction crashed = crash.crashed();
        riskAnswers("allow");
        ledgerAnswers(crash.ledger().toArray(String[]::new));
        String payee = Ibans.own();
        ActionInterference.crashAfter(crashed, payee);

        UUID paymentId = submit(payee);

        // the crashed action keeps its claim until the lease expires
        await().atMost(AWAIT).untilAsserted(() -> {
            assertThat(probe.row(paymentId).pendingAction()).isEqualTo(crashed.name());
            assertThat(calls(crashed, paymentId)).hasSize(1);
            assertThat(probe.leased(paymentId)).as("leased").isTrue();
        });
        CLOCK.advance(PAST_LEASE);

        await().atMost(AWAIT).untilAsserted(() -> assertThat(probe.row(paymentId)).isEqualTo(
                new Row(crash.state(), crash.reason(), null, true)));
        assertThat(probe.finalEvents(paymentId)).containsExactly(crash.finalEvent());
        // the call was made again, the same one; every other call once
        assertThat(calls(crashed, paymentId)).hasSize(2);
        assertSameCall(calls(crashed, paymentId));
        for (PendingAction other : List.of(PendingAction.CREATE_HOLD, PendingAction.SETTLE_ON_US,
                PendingAction.RELEASE_HOLD)) {
            if (other != crashed) {
                assertThat(calls(other, paymentId)).hasSizeLessThanOrEqualTo(1);
            }
        }
    }

    /** Both calls are one request: the same key, the same body (the ledger replays its stored answer). */
    private static void assertSameCall(List<LoggedRequest> calls) {
        assertThat(calls).extracting(call -> call.getHeader("Idempotency-Key")).containsOnly(
                calls.getFirst().getHeader("Idempotency-Key"));
        assertThat(calls).extracting(call -> JSON.readTree(call.getBodyAsString())).containsOnly(
                JSON.readTree(calls.getFirst().getBodyAsString()));
    }

    private List<LoggedRequest> calls(PendingAction action, UUID paymentId) {
        Function<UUID, List<LoggedRequest>> calls = switch (action) {
            case ASSESS_RISK -> id -> probe.riskCalls(id).stream()
                    .filter(call -> call.getBodyAsString().contains(":OUTGOING")).toList();
            case ASSESS_PAYEE_RISK -> id -> probe.riskCalls(id).stream()
                    .filter(call -> call.getBodyAsString().contains(":INCOMING")).toList();
            case CREATE_HOLD -> probe::holdCalls;
            case SETTLE_ON_US -> probe::settleOnUsCalls;
            case RELEASE_HOLD -> probe::releaseCalls;
            default -> throw new IllegalArgumentException(action + " is not an M2 action");
        };
        return calls.apply(paymentId);
    }

    /** An on-us payment: payer and payee at the own bank. */
    private UUID submit(String payee) {
        Map<String, Object> request = PaymentCommandIT.request(Ibans.own(), payee);
        String body = http().post().uri("/v1/payments").contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(request)).retrieve().body(String.class);
        return UUID.fromString(JSON.readTree(body).get("paymentId").asString());
    }
}
