package com.tpe.paymentorchestrator;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /v1/payments} and {@code GET /v1/payments/{paymentId}} (specs/11-payment-orchestrator.md §3.1, §5,
 * UC-01 6). risk-service is down and the clock does not move, so a submitted payment stays {@code RECEIVED}.
 */
class PaymentCommandIT extends OrchestratorStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    JdbcClient jdbc;

    /** risk-service answers 503: the runner's first attempt fails, and the payment waits for its retry. */
    @BeforeEach
    void riskIsDown() {
        riskAnswers("unavailable");
    }

    @Test
    void ORC_AC_01_submittingTheSameCommandTwiceReturnsTheSamePaymentAndCreatesOne() {
        Map<String, Object> request = request(Ibans.own(), Ibans.peer());

        ResponseEntity<String> first = submit(request);
        ResponseEntity<String> second = submit(request);

        assertThat(first.getStatusCode().value()).isEqualTo(201);
        assertThat(second.getStatusCode().value()).isEqualTo(200);
        JsonNode created = JSON.readTree(first.getBody());
        assertThat(JSON.readTree(second.getBody())).isEqualTo(created);
        assertThat(created.get("state").asString()).isEqualTo("RECEIVED");
        assertThat(jdbc.sql("SELECT count(*) FROM payments WHERE command_id = ?")
                .param(UUID.fromString((String) request.get("commandId"))).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void aRetryWithItsPropertiesInAnotherOrderIsTheSameRequest() {
        Map<String, Object> request = request(Ibans.own(), Ibans.peer());
        submit(request);
        Map<String, Object> reordered = new LinkedHashMap<>();
        List.copyOf(request.keySet()).reversed().forEach(key -> reordered.put(key, request.get(key)));

        assertThat(submit(reordered).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void theSameCommandWithAnotherRequestIsAConflict() {
        Map<String, Object> request = request(Ibans.own(), Ibans.peer());
        submit(request);
        request.put("amountMinor", 54321);

        ResponseEntity<String> conflict = submit(request);

        assertThat(conflict.getStatusCode().value()).isEqualTo(409);
        assertThat(JSON.readTree(conflict.getBody()).get("code").asString()).isEqualTo("COMMAND_CONFLICT");
    }

    @Test
    void aCreditorBankOutsideTheDirectoryIsNotReachable() {
        Map<String, Object> request = request(Ibans.own(), Ibans.fresh(Ibans.UNKNOWN_BANK));

        ResponseEntity<String> refused = submit(request);

        assertThat(refused.getStatusCode().value()).isEqualTo(422);
        assertThat(JSON.readTree(refused.getBody()).get("code").asString()).isEqualTo("CREDITOR_AGENT_NOT_REACHABLE");
        assertThat(jdbc.sql("SELECT count(*) FROM payments WHERE command_id = ?")
                .param(UUID.fromString((String) request.get("commandId"))).query(Long.class).single()).isZero();
    }

    @Test
    void anOnUsCreditorIsSettledInHouseAndTheTimeLimitsFollowTheOffsets() {
        Instant now = CLOCK.instant();

        JsonNode accepted = JSON.readTree(submit(request(Ibans.own(), Ibans.own())).getBody());

        UUID paymentId = UUID.fromString(accepted.get("paymentId").asString());
        assertThat(accepted.get("acceptedAt").asString()).isEqualTo(now.toString());
        assertThat(jdbc.sql("""
                        SELECT on_us, creditor_agent_bic, send_by = ?, deadline_at = ?, pending_action,
                               next_action_at IS NOT NULL
                        FROM payments WHERE payment_id = ?""")
                .param(Timestamp.from(now.plus(Duration.ofSeconds(10))))
                .param(Timestamp.from(now.plus(Duration.ofSeconds(20))))
                .param(paymentId)
                .query((rs, n) -> List.of(rs.getBoolean(1), rs.getString(2), rs.getBoolean(3), rs.getBoolean(4),
                        rs.getString(5), rs.getBoolean(6)))
                .single()).containsExactly(true, "TPEBDEFFXXX", true, true, "ASSESS_RISK", true);
        assertThat(get(paymentId).get("onUs").asBoolean()).isTrue();
    }

    @Test
    void theDeadlineTimerAndTheFirstTransitionAreWrittenWithThePayment() {
        Instant now = CLOCK.instant();

        UUID paymentId = paymentId(submit(request(Ibans.own(), Ibans.peer())));

        assertThat(jdbc.sql("SELECT type || '|' || status || '|' || (due_at = ?) FROM payment_timers WHERE payment_id = ?")
                .param(Timestamp.from(now.plus(Duration.ofSeconds(20)))).param(paymentId)
                .query(String.class).list()).containsExactly("OUTGOING_DEADLINE|SCHEDULED|true");
        assertThat(jdbc.sql("""
                        SELECT coalesce(from_state, '-') || '|' || to_state || '|' || cause || '|' || actor
                        FROM payment_transitions WHERE payment_id = ?""")
                .param(paymentId).query(String.class).list()).containsExactly("-|RECEIVED|SUBMITTED|system");
    }

    @Test
    void thePaymentKeepsTheTraceOfItsSubmitForTheWorkThatFollows() {
        UUID paymentId = paymentId(submit(request(Ibans.own(), Ibans.peer())));

        // ADR-014: the saga's actions and timers continue this trace
        assertThat(jdbc.sql("SELECT trace_context FROM payments WHERE payment_id = ?").param(paymentId)
                .query(String.class).single()).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCreditorBicComesFromTheDirectoryWhenAbsentAndIsKeptWhenGiven() {
        Map<String, Object> withoutBic = request(Ibans.own(), Ibans.peer());
        Map<String, Object> withBic = request(Ibans.own(), Ibans.peer());
        ((Map<String, Object>) withBic.get("creditor")).put("bic", "PEERDEFF");

        UUID fromDirectory = paymentId(submit(withoutBic));
        UUID given = paymentId(submit(withBic));

        assertThat(creditorAgentBic(fromDirectory)).isEqualTo("PEERDEFFXXX");
        assertThat(creditorAgentBic(given)).isEqualTo("PEERDEFF");
    }

    @Test
    void theViewHasTheFieldsOfTheSpec() {
        String debtor = Ibans.own();
        String creditor = Ibans.peer();
        Map<String, Object> request = request(debtor, creditor);
        JsonNode accepted = JSON.readTree(submit(request).getBody());
        UUID paymentId = UUID.fromString(accepted.get("paymentId").asString());

        JsonNode view = get(paymentId);

        assertThat(view.get("paymentId").asString()).isEqualTo(paymentId.toString());
        assertThat(view.get("direction").asString()).isEqualTo("OUTGOING");
        assertThat(view.get("onUs").asBoolean()).isFalse();
        assertThat(view.get("state").asString()).isEqualTo("RECEIVED");
        assertThat(view.get("reasonCode").isNull()).isTrue();
        assertThat(view.get("txId").asString()).isEqualTo(accepted.get("txId").asString()).startsWith("TPE")
                .hasSize(29);
        assertThat(view.get("uetr").asString())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(view.get("endToEndId").asString()).isEqualTo("INV-2026-0042");
        assertThat(view.get("amountMinor").asLong()).isEqualTo(12345);
        assertThat(view.get("currency").asString()).isEqualTo("EUR");
        assertThat(view.get("debtor").get("name").asString()).isEqualTo("Anna Schmidt");
        assertThat(view.get("debtor").get("iban").asString()).isEqualTo(debtor);
        assertThat(view.get("creditor").get("name").asString()).isEqualTo("Max Mustermann");
        assertThat(view.get("creditor").get("iban").asString()).isEqualTo(creditor);
        assertThat(view.get("remittanceInformation").asString()).isEqualTo("Invoice 42");
        assertThat(view.get("acceptedAt").asString()).isEqualTo(accepted.get("acceptedAt").asString());
        assertThat(view.get("finalisedAt").isNull()).isTrue();
    }

    @Test
    void anUnknownPaymentIsNotFound() {
        ResponseEntity<String> response = http().get().uri("/v1/payments/{id}", UUID.randomUUID()).retrieve()
                .toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(JSON.readTree(response.getBody()).get("code").asString()).isEqualTo("PAYMENT_NOT_FOUND");
    }

    @Test
    void aSubmitWithoutItsRequiredFieldsIsRefused() {
        ResponseEntity<String> response = submit(Map.of("clientId", "mobile-app", "amountMinor", 100,
                "currency", "EUR"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        JsonNode problem = JSON.readTree(response.getBody());
        assertThat(problem.get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(problem.get("errors").valueStream().map(error -> error.get("field").asString()))
                .containsExactly("commandId", "endToEndId", "debtor", "creditor", "vopReference", "vopResult");
    }

    @Test
    void aSubmitWithoutAnAmountIsRefused() {
        // the amount is an unwrapped Money: a refused one is a 400 without a field path
        Map<String, Object> request = request(Ibans.own(), Ibans.peer());
        request.remove("amountMinor");

        ResponseEntity<String> response = submit(request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(JSON.readTree(response.getBody()).get("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void anInvalidIbanIsReportedAsItsField() {
        Map<String, Object> request = request(Ibans.own(), "DE00100100100000000001");

        ResponseEntity<String> response = submit(request);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(JSON.readTree(response.getBody()).get("errors").get(0).get("field").asString())
                .isEqualTo("creditor.iban");
    }

    static Map<String, Object> request(String debtorIban, String creditorIban) {
        Map<String, Object> request = new HashMap<>();
        request.put("commandId", UUID.randomUUID().toString());
        request.put("clientId", "mobile-app");
        request.put("endToEndId", "INV-2026-0042");
        request.put("debtor", new HashMap<>(Map.of("name", "Anna Schmidt", "iban", debtorIban)));
        request.put("creditor", new HashMap<>(Map.of("name", "Max Mustermann", "iban", creditorIban)));
        request.put("amountMinor", 12345);
        request.put("currency", "EUR");
        request.put("remittanceInformation", "Invoice 42");
        request.put("vopReference", "VOP-01JABCDEFGHJKMNPQRSTVWXYZ");
        request.put("vopResult", "CLOSE_MATCH");
        return request;
    }

    private ResponseEntity<String> submit(Map<String, Object> request) {
        return http().post().uri("/v1/payments").contentType(MediaType.APPLICATION_JSON)
                .body(JSON.writeValueAsString(request)).retrieve().toEntity(String.class);
    }

    private JsonNode get(UUID paymentId) {
        return JSON.readTree(http().get().uri("/v1/payments/{id}", paymentId).retrieve().body(String.class));
    }

    private static UUID paymentId(ResponseEntity<String> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return UUID.fromString(JSON.readTree(response.getBody()).get("paymentId").asString());
    }

    private String creditorAgentBic(UUID paymentId) {
        return jdbc.sql("SELECT creditor_agent_bic FROM payments WHERE payment_id = ?").param(paymentId)
                .query(String.class).single();
    }
}
