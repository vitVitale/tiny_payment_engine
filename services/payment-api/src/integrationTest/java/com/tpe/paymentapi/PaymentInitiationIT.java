package com.tpe.paymentapi;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.tpe.paymentdomain.CommandId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /v1/payments} with JSON (specs/10-payment-api.md §3.1, UC-01 1–8): the token, ownership, the
 * {@code Idempotency-Key}, validation, the VoP reference check and the submit, against WireMock as the orchestrator and
 * vop-service with the mappings P-01 and P-02 check.
 */
@ExtendWith(OutputCaptureExtension.class)
@SuppressWarnings("unchecked")
class PaymentInitiationIT extends PaymentApiStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String DEBTOR = "DE36100100100000000001";
    private static final String CREDITOR = "DE63200200200000000001";
    /** A bank code the directory does not have (Commerzbank). */
    private static final String UNKNOWN_BANK = "DE89370400440532013000";
    private static final String REFERENCE = "VOP-01JABCDEFGHJKMNPQRSTVW0001";
    private static final String WRITE = "payments:write";

    @BeforeEach
    void answers() {
        vopAnswers("check-valid");
        orchestratorAnswers("submit-created");
    }

    @Test
    void API_AC_01_aValidPaymentWithAValidReferenceIsAcceptedAndSubmittedOnce() {
        Map<String, Object> body = payment();
        // a client's word for the VoP result is never taken (§5.3)
        body.put("vopResult", "MATCH");

        ResponseEntity<JsonNode> response = initiate("key-api-ac-01", body);

        assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(202);
        assertThat(response.getHeaders().getLocation())
                .hasToString("/v1/payments/0192f0e4-7b1c-7cc3-a1b2-000000000101");
        assertThat(response.getBody()).isEqualTo(JSON.readTree("""
                {"paymentId":"0192f0e4-7b1c-7cc3-a1b2-000000000101","status":"PDNG","internalState":"RECEIVED",
                 "acceptedAt":"2026-11-14T10:00:00.123Z"}"""));
        List<LoggedRequest> checks = VOP.findAll(anyRequestedFor(anyUrl()));
        assertThat(checks).singleElement().satisfies(check -> {
            assertThat(check.getUrl()).isEqualTo("/v1/verifications/" + REFERENCE + "/check");
            assertThat(JSON.readTree(check.getBodyAsString())).isEqualTo(JSON.readTree("""
                    {"debtorIban":"%s","payeeIban":"%s","payeeName":"Max Mustermann"}""".formatted(DEBTOR,
                    CREDITOR)));
        });
        assertThat(submits()).singleElement().satisfies(submit -> assertThat(JSON.readTree(submit.getBodyAsString()))
                .isEqualTo(JSON.readTree("""
                        {"commandId":"%s","clientId":"mobile-app","endToEndId":"INV-2026-0042",
                         "debtor":{"name":"Anna Schmidt","iban":"%s"},
                         "creditor":{"name":"Max Mustermann","iban":"%s","bic":"PEERDEFFXXX"},
                         "amountMinor":12345,"currency":"EUR","remittanceInformation":"Invoice 42",
                         "vopReference":"%s","vopResult":"CLOSE_MATCH"}""".formatted(
                        CommandId.derive(Tokens.CLIENT, "key-api-ac-01").value(), DEBTOR, CREDITOR, REFERENCE))));
    }

    /** UC-01 6a: the earlier attempt reached the orchestrator; the payment is answered as it is now. */
    @Test
    void thePaymentOfAnEarlierSubmitIsAcceptedWithItsCurrentStatus() {
        orchestratorAnswers("submit-existing");

        ResponseEntity<JsonNode> response = initiate("key-earlier", payment());

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getBody().get("status").asString()).isEqualTo("ACSC");
        assertThat(response.getBody().get("internalState").asString()).isEqualTo("SETTLED");
    }

    @ParameterizedTest
    @CsvSource({"check-expired, EXPIRED", "check-mismatch, MISMATCH", "check-unknown, UNKNOWN"})
    void API_AC_07_aReferenceThatDoesNotCoverThePaymentIsVopRequired(String answer, String problem) {
        vopAnswers(answer);

        ResponseEntity<JsonNode> response = initiate("key-api-ac-07", payment());

        assertProblem(response, 422, "VOP_REQUIRED");
        assertThat(response.getBody().get("detail").asString()).isEqualTo(problem);
        assertThat(submits()).isEmpty();
    }

    @Test
    void aPaymentWithoutAReferenceIsVopRequiredWithoutAsking() {
        Map<String, Object> body = payment();
        body.remove("vopReference");

        ResponseEntity<JsonNode> response = initiate("key-no-reference", body);

        assertProblem(response, 422, "VOP_REQUIRED");
        assertThat(response.getBody().get("detail").asString()).isEqualTo("MISSING");
        assertThat(VOP.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    /** API-AC-05, the initiation half (UC-01 1a). */
    @Test
    void aDebtorAccountNotInTheTokenIsForbidden() {
        ResponseEntity<JsonNode> response = initiate(Tokens.customer(WRITE, "DE89370400440532013000"), "key-foreign",
                payment());

        assertProblem(response, 403, "DEBTOR_ACCOUNT_NOT_OWNED");
        assertNothingAsked();
    }

    @Test
    void theDebtorIbanIsOwnedInAnyNotation() {
        ResponseEntity<JsonNode> response = initiate(Tokens.customer(WRITE, DEBTOR), "key-notation",
                payment(p -> debtorIban(p, "de36 1001 0010 0000 0000 01")));

        assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(202);
    }

    @Test
    void withoutATokenThePaymentIsUnauthorised() {
        ResponseEntity<JsonNode> response = initiate(null, "key-no-token", payment());

        assertProblem(response, 401, "UNAUTHORIZED");
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).startsWith("Bearer");
        assertNothingAsked();
    }

    @Test
    void aTokenOfAnotherIssuerIsUnauthorised() {
        ResponseEntity<JsonNode> response = initiate(Tokens.foreign(WRITE, DEBTOR), "key-foreign-issuer",
                payment());

        assertProblem(response, 401, "UNAUTHORIZED");
        assertNothingAsked();
    }

    @Test
    void aTokenWithoutTheWriteScopeIsForbidden() {
        ResponseEntity<JsonNode> response = initiate(Tokens.customer("payments:read vop:verify", DEBTOR),
                "key-read-scope", payment());

        assertProblem(response, 403, "FORBIDDEN");
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).contains("insufficient_scope");
        assertNothingAsked();
    }

    @Test
    void aTokenWithoutAClientIdIsForbidden() {
        ResponseEntity<JsonNode> response = initiate(Tokens.withoutClientId(WRITE, DEBTOR), "key-no-client",
                payment());

        assertProblem(response, 403, "FORBIDDEN");
        assertNothingAsked();
    }

    @Test
    void aTokenWithoutAnAccountsClaimIsForbidden() {
        ResponseEntity<JsonNode> response = initiate(Tokens.withoutAccounts(WRITE), "key-no-accounts", payment());

        assertProblem(response, 403, "FORBIDDEN");
        assertNothingAsked();
    }

    @Test
    void withoutAnIdempotencyKeyThePaymentIsRefused() {
        assertProblem(initiate(null, payment()), 400, "IDEMPOTENCY_KEY_MISSING");
        assertProblem(initiate(" ", payment()), 400, "IDEMPOTENCY_KEY_MISSING");
        assertNothingAsked();
    }

    @ParameterizedTest
    @ValueSource(strings = {"key with spaces", "key/slash", "k"
            + "0123456789012345678901234567890123456789012345678901234567890123"})
    void aMalformedIdempotencyKeyIsRefused(String key) {
        assertProblem(initiate(key, payment()), 400, "IDEMPOTENCY_KEY_INVALID");
        assertNothingAsked();
    }

    /** API-AC-06, the JSON half: every rule is PaymentValidatorTest's; here the answer. */
    @Test
    void invalidFieldsAreAllReportedWithTheirCodes() {
        ResponseEntity<JsonNode> response = initiate("key-invalid", payment(p -> {
            p.remove("endToEndId");
            p.put("amount", Map.of("value", "0.001", "currency", "USD"));
            ((Map<String, Object>) p.get("creditor")).put("iban", DEBTOR);
        }));

        assertProblem(response, 400, "VALIDATION_FAILED");
        assertThat(response.getBody().get("errors")).isEqualTo(JSON.readTree("""
                [{"field":"amount.currency","code":"UNSUPPORTED_CURRENCY"},
                 {"field":"amount.value","code":"INVALID_AMOUNT"},
                 {"field":"creditor.bic","code":"INVALID_BIC"},
                 {"field":"creditor.iban","code":"SAME_DEBTOR_AND_CREDITOR"},
                 {"field":"endToEndId","code":"REQUIRED"}]"""));
        assertNothingAsked();
    }

    @Test
    void anUnreadableBodyIsValidationFailed() {
        ResponseEntity<JsonNode> response = http().post().uri("/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, Tokens.customer(WRITE, DEBTOR))
                .header("Idempotency-Key", "key-unreadable")
                .contentType(MediaType.APPLICATION_JSON).body("{\"debtor\": ")
                .retrieve().toEntity(JsonNode.class);

        assertProblem(response, 400, "VALIDATION_FAILED");
        assertThat(response.getBody().get("errors")).isEmpty();
    }

    @Test
    void aCreditorBankOutsideTheDirectoryIsNotReachable() {
        ResponseEntity<JsonNode> response = initiate("key-unknown-bank", payment(p -> {
            Map<String, Object> creditor = (Map<String, Object>) p.get("creditor");
            creditor.put("iban", UNKNOWN_BANK);
            creditor.remove("bic");
        }));

        assertProblem(response, 422, "CREDITOR_AGENT_NOT_REACHABLE");
        assertNothingAsked();
    }

    @Test
    void theOrchestratorRefusingTheCreditorBankIsNotReachable() {
        orchestratorAnswers("submit-creditor-agent-not-reachable");

        assertProblem(initiate("key-orchestrator-refuses", payment()), 422, "CREDITOR_AGENT_NOT_REACHABLE");
    }

    @Test
    void aKeyOfAnotherRequestIsReused() {
        orchestratorAnswers("submit-command-conflict");

        assertProblem(initiate("key-conflict", payment()), 422, "IDEMPOTENCY_KEY_REUSED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"submit-unavailable", "submit-timeout", "submit-connection-reset"})
    void anOrchestratorThatDoesNotAnswerIsUnavailableWithRetryAfter(String answer) {
        orchestratorAnswers(answer);

        ResponseEntity<JsonNode> response = initiate("key-orchestrator-down", payment());

        assertProblem(response, 503, "ORCHESTRATOR_UNAVAILABLE");
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        // it may have arrived: the client's retry carries the same commandId (UC-01 5a, 6a)
        assertThat(submits()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"check-unavailable", "check-timeout"})
    void aReferenceCheckThatFailsTwiceIsVopUnavailableWithRetryAfter(String answer) {
        vopAnswers(answer);

        ResponseEntity<JsonNode> response = initiate("key-vop-down", payment());

        assertProblem(response, 503, "VOP_UNAVAILABLE");
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(VOP.findAll(anyRequestedFor(anyUrl()))).hasSize(2);
        assertThat(submits()).isEmpty();
    }

    /** ADR-014: the channel's trace is linked, never continued; the orchestrator sees payment-api's own trace. */
    @Test
    void aChannelTraceparentIsNotTheParentOfThePaymentsTrace() {
        String channelTrace = UUID.randomUUID().toString().replace("-", "");
        ResponseEntity<JsonNode> response = http().post().uri("/v1/payments")
                .header(HttpHeaders.AUTHORIZATION, Tokens.customer(WRITE, DEBTOR))
                .header("Idempotency-Key", "key-traceparent")
                .header("traceparent", "00-" + channelTrace + "-00f067aa0ba902b7-01")
                .contentType(MediaType.APPLICATION_JSON).body(payment())
                .retrieve().toEntity(JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(submits()).singleElement().satisfies(submit -> {
            String traceparent = submit.getHeader("traceparent");
            assertThat(traceparent).as("the orchestrator is called within payment-api's trace").isNotNull();
            assertThat(traceparent.split("-")[1]).isNotEqualTo(channelTrace);
        });
    }

    @Test
    void requestBodiesAreNeverLogged(CapturedOutput output) {
        initiate("key-logged", payment());
        initiate("key-logged-invalid", payment(p -> p.put("amount", Map.of("value", "-1", "currency", "EUR"))));
        vopAnswers("check-mismatch");
        initiate("key-logged-vop", payment());

        assertThat(output.getAll()).doesNotContain(DEBTOR, CREDITOR, "Anna Schmidt", "Max Mustermann",
                "Invoice 42");
    }

    private ResponseEntity<JsonNode> initiate(String idempotencyKey, Map<String, Object> body) {
        return initiate(Tokens.customer(WRITE, DEBTOR), idempotencyKey, body);
    }

    private ResponseEntity<JsonNode> initiate(String authorization, String idempotencyKey, Map<String, Object> body) {
        var request = http().post().uri("/v1/payments").contentType(MediaType.APPLICATION_JSON);
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (idempotencyKey != null) {
            request.header("Idempotency-Key", idempotencyKey);
        }
        return request.body(body).retrieve().toEntity(JsonNode.class);
    }

    private static List<LoggedRequest> submits() {
        return ORCHESTRATOR.findAll(postRequestedFor(urlEqualTo("/v1/payments")));
    }

    private static void assertNothingAsked() {
        assertThat(VOP.findAll(anyRequestedFor(anyUrl()))).as("vop-service asked").isEmpty();
        assertThat(ORCHESTRATOR.findAll(anyRequestedFor(anyUrl()))).as("orchestrator asked").isEmpty();
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().get("code").asString()).isEqualTo(code);
        assertThat(response.getBody().get("status").asInt()).isEqualTo(status);
    }

    /** The JSON body of §3.1. */
    static Map<String, Object> payment() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("endToEndId", "INV-2026-0042");
        body.put("debtor", new LinkedHashMap<>(Map.of("name", "Anna Schmidt", "iban", DEBTOR)));
        body.put("creditor", new LinkedHashMap<>(Map.of("name", "Max Mustermann", "iban", CREDITOR, "bic",
                "PEERDEFFXXX")));
        body.put("amount", Map.of("value", "123.45", "currency", "EUR"));
        body.put("remittanceInformation", "Invoice 42");
        body.put("vopReference", REFERENCE);
        return body;
    }

    private static Map<String, Object> payment(Consumer<Map<String, Object>> change) {
        Map<String, Object> body = payment();
        change.accept(body);
        return body;
    }

    private static void debtorIban(Map<String, Object> body, String iban) {
        ((Map<String, Object>) body.get("debtor")).put("iban", iban);
    }
}
