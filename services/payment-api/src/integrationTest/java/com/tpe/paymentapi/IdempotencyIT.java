package com.tpe.paymentapi;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.tpe.paymentapi.application.idempotency.IdempotencyRetention;
import com.tpe.paymentdomain.CommandId;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The idempotency guard of {@code POST /v1/payments} (specs/10-payment-api.md §5.1, UC-01 2a, 2b, 5a, 6a) against
 * PostgreSQL, with WireMock as the orchestrator and vop-service answering the mappings P-01 and P-02 check.
 */
class IdempotencyIT extends PaymentApiStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DEBTOR = "DE36100100100000000001";
    private static final String WRITE = "payments:write";
    private static final String FIRST = "0192f0e4-7b1c-7cc3-a1b2-000000000101";

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private IdempotencyRetention retention;

    @BeforeEach
    void answers() {
        vopAnswers("check-valid");
        orchestratorAnswers("submit-created");
    }

    @Test
    void API_AC_03_theSameKeyAndBodyAnswerTheSamePaymentWithItsCurrentStatus() {
        ResponseEntity<JsonNode> first = initiate("key-api-ac-03", PaymentInitiationIT.payment());
        orchestratorAnswers("submit-created", "status-settled");
        VOP.resetRequests();

        ResponseEntity<JsonNode> again = initiate("key-api-ac-03", PaymentInitiationIT.payment());

        assertThat(first.getStatusCode().value()).as("%s", first.getBody()).isEqualTo(202);
        assertThat(again.getStatusCode().value()).as("%s", again.getBody()).isEqualTo(202);
        assertThat(again.getHeaders().getLocation()).hasToString("/v1/payments/" + FIRST);
        assertThat(again.getBody()).isEqualTo(JSON.readTree("""
                {"paymentId":"%s","status":"ACSC","internalState":"SETTLED",
                 "acceptedAt":"2026-11-14T10:00:00.123Z"}""".formatted(FIRST)));
        assertThat(submits()).as("no second orchestrator payment").isEmpty();
        assertThat(VOP.findAll(anyRequestedFor(anyUrl()))).as("no second reference check").isEmpty();
    }

    @Test
    void aReplayWhoseStatusReadFailsAnswersTheStoredResponse() {
        ResponseEntity<JsonNode> first = initiate("key-replay-stored", PaymentInitiationIT.payment());
        orchestratorAnswers("submit-created", "status-unavailable");

        ResponseEntity<JsonNode> again = initiate("key-replay-stored", PaymentInitiationIT.payment());

        assertThat(again.getStatusCode().value()).isEqualTo(202);
        assertThat(again.getBody()).isEqualTo(first.getBody());
        assertThat(submits()).isEmpty();
    }

    /** Property order and whitespace are not the request. */
    @Test
    void theSameBodyInAnotherPropertyOrderIsTheSameRequest() {
        initiate("key-order", PaymentInitiationIT.payment());
        orchestratorAnswers("submit-created", "status-settled");
        String reordered = """
                { "vopReference": "VOP-01JABCDEFGHJKMNPQRSTVW0001", "remittanceInformation": "Invoice 42",
                  "amount": { "currency": "EUR", "value": "123.45" },
                  "creditor": { "bic": "PEERDEFFXXX", "iban": "DE63200200200000000001", "name": "Max Mustermann" },
                  "debtor": { "iban": "DE36100100100000000001", "name": "Anna Schmidt" },
                  "endToEndId": "INV-2026-0042" }""";

        ResponseEntity<JsonNode> again = initiate("key-order", reordered);

        assertThat(again.getStatusCode().value()).as("%s", again.getBody()).isEqualTo(202);
        assertThat(submits()).isEmpty();
    }

    @Test
    void API_AC_04_theSameKeyWithAnotherBodyIsReused() {
        initiate("key-api-ac-04", PaymentInitiationIT.payment());
        orchestratorAnswers("submit-created");
        Map<String, Object> other = PaymentInitiationIT.payment();
        other.put("amount", Map.of("value", "123.46", "currency", "EUR"));

        assertProblem(initiate("key-api-ac-04", other), 422, "IDEMPOTENCY_KEY_REUSED");
        assertThat(ORCHESTRATOR.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void API_AC_08_aRetryAfterTheOrchestratorRecoversIsAccepted() {
        orchestratorAnswers("submit-unavailable");
        ResponseEntity<JsonNode> down = initiate("key-api-ac-08-down", PaymentInitiationIT.payment());
        orchestratorAnswers("submit-created");

        ResponseEntity<JsonNode> retry = initiate("key-api-ac-08-down", PaymentInitiationIT.payment());

        assertProblem(down, 503, "ORCHESTRATOR_UNAVAILABLE");
        assertThat(down.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(retry.getStatusCode().value()).as("%s", retry.getBody()).isEqualTo(202);
        assertThat(retry.getBody().get("paymentId").asString()).isEqualTo(FIRST);
    }

    /** UC-01 6a: the first submit arrived, its answer did not; the retry carries the same {@code commandId}. */
    @Test
    void API_AC_08_aRetryAfterALostResponseAnswersThePaymentOfTheFirstSubmit() {
        orchestratorAnswers("submit-timeout");
        ResponseEntity<JsonNode> lost = initiate("key-api-ac-08-lost", PaymentInitiationIT.payment());
        List<LoggedRequest> firstSubmit = submits();
        orchestratorAnswers("submit-existing");

        ResponseEntity<JsonNode> retry = initiate("key-api-ac-08-lost", PaymentInitiationIT.payment());

        assertProblem(lost, 503, "ORCHESTRATOR_UNAVAILABLE");
        assertThat(retry.getStatusCode().value()).as("%s", retry.getBody()).isEqualTo(202);
        assertThat(retry.getBody().get("paymentId").asString()).isEqualTo("0192f0e4-7b1c-7cc3-a1b2-000000000102");
        String commandId = CommandId.derive(Tokens.CLIENT, "key-api-ac-08-lost").value().toString();
        assertThat(firstSubmit).singleElement().satisfies(submit -> assertThat(commandId(submit)).isEqualTo(commandId));
        assertThat(submits()).singleElement().satisfies(submit -> assertThat(commandId(submit)).isEqualTo(commandId));
    }

    @Test
    void API_AC_10_twoConcurrentRequestsWithANewKeyMakeOnePayment() throws Exception {
        orchestratorAnswersAfter(Duration.ofMillis(500), "submit-created");
        CountDownLatch go = new CountDownLatch(1);

        List<ResponseEntity<JsonNode>> responses;
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = CompletableFuture.supplyAsync(() -> concurrently(go), threads);
            var second = CompletableFuture.supplyAsync(() -> concurrently(go), threads);
            go.countDown();
            responses = List.of(first.get(), second.get());
        }

        assertThat(responses).filteredOn(response -> response.getStatusCode().value() == 202)
                .isNotEmpty()
                .allSatisfy(response -> assertThat(response.getBody().get("paymentId").asString()).isEqualTo(FIRST));
        assertThat(responses).allSatisfy(response -> {
            int status = response.getStatusCode().value();
            if (status != 202) {
                assertProblem(response, 409, "IDEMPOTENCY_KEY_IN_PROGRESS");
            }
        });
        assertThat(submits()).as("never two payments").hasSize(1);
    }

    @Test
    void aRefusedRequestCanBeCorrectedUnderTheSameKey() {
        Map<String, Object> invalid = PaymentInitiationIT.payment();
        invalid.put("amount", Map.of("value", "-1", "currency", "EUR"));

        assertProblem(initiate("key-corrected", invalid), 400, "VALIDATION_FAILED");
        vopAnswers("check-expired");
        assertProblem(initiate("key-corrected", PaymentInitiationIT.payment()), 422, "VOP_REQUIRED");
        vopAnswers("check-valid");
        ResponseEntity<JsonNode> corrected = initiate("key-corrected", PaymentInitiationIT.payment());

        assertThat(corrected.getStatusCode().value()).as("%s", corrected.getBody()).isEqualTo(202);
    }

    /** As a request that crashed after its claim leaves it. */
    @Test
    void anAbandonedKeyInProgressIsTakenOverAfterTenSeconds() {
        jdbc.sql("""
                INSERT INTO idempotency_keys (client_id, idempotency_key, request_hash, status, created_at, expires_at)
                VALUES (?, 'key-abandoned', ?, 'IN_PROGRESS', ?, ?)""")
                .params(Tokens.CLIENT, "0".repeat(64), START.atOffset(ZoneOffset.UTC),
                        START.plus(Duration.ofHours(24)).atOffset(ZoneOffset.UTC))
                .update();
        CLOCK.advance(Duration.ofSeconds(9));

        assertProblem(initiate("key-abandoned", PaymentInitiationIT.payment()), 409, "IDEMPOTENCY_KEY_IN_PROGRESS");
        assertThat(submits()).isEmpty();

        CLOCK.advance(Duration.ofSeconds(1));
        ResponseEntity<JsonNode> takenOver = initiate("key-abandoned", PaymentInitiationIT.payment());

        assertThat(takenOver.getStatusCode().value()).as("%s", takenOver.getBody()).isEqualTo(202);
        assertThat(submits()).hasSize(1);
    }

    @Test
    void anExpiredKeyIsCleanedUp() {
        initiate("key-expired", PaymentInitiationIT.payment());
        CLOCK.advance(Duration.ofHours(24));
        assertThat(retention.purge()).isZero();

        CLOCK.advance(Duration.ofMillis(1));
        assertThat(retention.purge()).isEqualTo(1);

        assertThat(jdbc.sql("SELECT count(*) FROM idempotency_keys").query(Integer.class).single()).isZero();
    }

    @Test
    void keysAreTheClientsOwn() {
        initiate("key-of-a-client", PaymentInitiationIT.payment());
        orchestratorAnswers("submit-created");

        ResponseEntity<JsonNode> otherClient = http().post().uri("/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, Tokens.ofClient("web-banking", WRITE, DEBTOR))
                .header("Idempotency-Key", "key-of-a-client")
                .contentType(MediaType.APPLICATION_JSON).body(PaymentInitiationIT.payment())
                .retrieve().toEntity(JsonNode.class);

        assertThat(otherClient.getStatusCode().value()).as("%s", otherClient.getBody()).isEqualTo(202);
        assertThat(submits()).hasSize(1);
    }

    private ResponseEntity<JsonNode> concurrently(CountDownLatch go) {
        try {
            go.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return initiate("key-api-ac-10", PaymentInitiationIT.payment());
    }

    private ResponseEntity<JsonNode> initiate(String idempotencyKey, Object body) {
        return http().post().uri("/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, Tokens.customer(WRITE, DEBTOR))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toEntity(JsonNode.class);
    }

    private static List<LoggedRequest> submits() {
        return ORCHESTRATOR.findAll(postRequestedFor(urlEqualTo("/v1/payments")));
    }

    private static String commandId(LoggedRequest submit) {
        return JSON.readTree(submit.getBodyAsString()).get("commandId").asString();
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().get("code").asString()).isEqualTo(code);
    }
}
