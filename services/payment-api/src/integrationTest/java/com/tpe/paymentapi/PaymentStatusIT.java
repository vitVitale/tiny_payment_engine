package com.tpe.paymentapi;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.stubbing.StubMapping;
import com.tpe.iso20022model.IsoCodec;
import com.tpe.iso20022model.XsdValidator;
import com.tpe.iso20022model.pain002v10.Document;
import com.tpe.iso20022model.pain002v10.PaymentTransaction105;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code GET /v1/payments/{paymentId}} and {@code /pain.002} (specs/10-payment-api.md §3.2, §3.3, UC-07), against
 * WireMock as the orchestrator with the mappings P-01 checks.
 */
class PaymentStatusIT extends PaymentApiStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String ID = "0192f0e4-7b1c-7cc3-a1b2-000000000101";
    private static final String DEBTOR = "DE36100100100000000001";
    private static final String READ = "payments:read";

    /** UC-07 3, for every internal state an outgoing payment has: the pending view with its state swapped in. */
    @ParameterizedTest
    @CsvSource({"RECEIVED, PDNG", "RISK_CHECKED, PDNG", "FUNDS_RESERVED, PDNG", "SENT_TO_CSM, PDNG",
            "INVESTIGATING, PDNG"})
    void API_AC_09_aPaymentBeforeItsOutcomeIsPending(String state, String status) {
        ORCHESTRATOR.resetAll();
        ORCHESTRATOR.addStubMapping(StubMapping.buildFrom(
                mapping("status-pending").replace("\"SENT_TO_CSM\"", "\"" + state + "\"")));

        ResponseEntity<JsonNode> response = status(Tokens.customer(READ, DEBTOR));

        assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
        assertThat(response.getBody().get("status").asString()).isEqualTo(status);
        assertThat(response.getBody().get("internalState").asString()).isEqualTo(state);
        assertThat(response.getBody().get("reasonCode").isNull()).isTrue();
        assertThat(response.getBody().get("finalisedAt").isNull()).isTrue();
    }

    @Test
    void API_AC_09_aSettledPaymentIsAcsc() {
        orchestratorAnswers("status-settled");

        ResponseEntity<JsonNode> response = status(Tokens.customer(READ, DEBTOR));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody()).isEqualTo(JSON.readTree("""
                {"paymentId":"%s","status":"ACSC","reasonCode":null,"internalState":"SETTLED",
                 "endToEndId":"INV-2026-0042","amount":{"value":"123.45","currency":"EUR"},
                 "creditor":{"name":"Max Mustermann","iban":"DE63200200200000000001"},
                 "acceptedAt":"2026-11-14T10:00:00.123Z","finalisedAt":"2026-11-14T10:00:01.500Z"}""".formatted(ID)));
    }

    @Test
    void API_AC_09_aRejectedPaymentIsRjctWithItsReasonCode() {
        orchestratorAnswers("status-rejected");

        ResponseEntity<JsonNode> response = status(Tokens.customer(READ, DEBTOR));

        assertThat(response.getBody().get("status").asString()).isEqualTo("RJCT");
        assertThat(response.getBody().get("reasonCode").asString()).isEqualTo("AM04");
        assertThat(response.getBody().get("internalState").asString()).isEqualTo("REJECTED");
    }

    /** UC-07 1a: a payment from an account not in the token is not found, as an unknown one is. */
    @Test
    void API_AC_05_theStatusOfAForeignPaymentIsNotFound() {
        orchestratorAnswers("status-settled");

        assertProblem(status(Tokens.customer(READ, "DE63200200200000000001")), 404, "PAYMENT_NOT_FOUND");
        assertProblem(pain002(Tokens.customer(READ, "DE63200200200000000001")), 404, "PAYMENT_NOT_FOUND");
    }

    /** A client that accepts only XML gets the same {@code 404} problem, so nothing tells the cases apart. */
    @Test
    void thePain002OfAForeignPaymentIsNotFoundForAnXmlOnlyClient() {
        orchestratorAnswers("status-settled");

        ResponseEntity<String> response = http().get().uri("/v1/payments/{id}/pain.002", ID)
                .header(HttpHeaders.AUTHORIZATION, Tokens.customer(READ, "DE63200200200000000001"))
                .accept(MediaType.APPLICATION_XML)
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(JSON.readTree(response.getBody()).get("code").asString()).isEqualTo("PAYMENT_NOT_FOUND");
    }

    @Test
    void anUnknownPaymentIsNotFound() {
        orchestratorAnswers("status-not-found");

        assertProblem(status(Tokens.customer(READ, DEBTOR)), 404, "PAYMENT_NOT_FOUND");
    }

    @Test
    void anIdThatIsNoUuidIsNotFoundWithoutAsking() {
        ResponseEntity<JsonNode> response = http().get().uri("/v1/payments/{id}", "not-a-uuid")
                .header(HttpHeaders.AUTHORIZATION, Tokens.customer(READ, DEBTOR))
                .retrieve().toEntity(JsonNode.class);

        assertProblem(response, 404, "PAYMENT_NOT_FOUND");
        assertThat(ORCHESTRATOR.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void theStatusNeedsTheReadScope() {
        ResponseEntity<JsonNode> response = status(Tokens.customer("payments:write", DEBTOR));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(ORCHESTRATOR.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    /** A read: retried once, then unavailable. */
    @Test
    void anOrchestratorThatDoesNotAnswerIsUnavailable() {
        orchestratorAnswers("status-unavailable");

        ResponseEntity<JsonNode> response = status(Tokens.customer(READ, DEBTOR));

        assertProblem(response, 503, "ORCHESTRATOR_UNAVAILABLE");
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(ORCHESTRATOR.findAll(anyRequestedFor(anyUrl()))).hasSize(2);
    }

    @Test
    void thePain002OfASettledPaymentValidatesAndIsAcsc() {
        orchestratorAnswers("status-settled");

        ResponseEntity<String> response = http().get().uri("/v1/payments/{id}/pain.002", ID)
                .header(HttpHeaders.AUTHORIZATION, Tokens.customer(READ, DEBTOR))
                .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_XML)).isTrue();
        assertThat(new XsdValidator().validate(response.getBody())).isEmpty();
        PaymentTransaction105 transaction = transaction(response.getBody());
        assertThat(transaction.getTxSts()).isEqualTo("ACSC");
        assertThat(transaction.getOrgnlEndToEndId()).isEqualTo("INV-2026-0042");
        assertThat(transaction.getOrgnlUETR()).isEqualTo("eb6305c9-1f7f-49de-aed0-16487c27b42d");
        assertThat(transaction.getStsRsnInf()).isEmpty();
    }

    @Test
    void thePain002OfARejectedPaymentValidatesAndCarriesTheReason() {
        orchestratorAnswers("status-rejected");

        ResponseEntity<String> response = http().get().uri("/v1/payments/{id}/pain.002", ID)
                .header(HttpHeaders.AUTHORIZATION, Tokens.customer(READ, DEBTOR))
                .retrieve().toEntity(String.class);

        assertThat(new XsdValidator().validate(response.getBody())).isEmpty();
        PaymentTransaction105 transaction = transaction(response.getBody());
        assertThat(transaction.getTxSts()).isEqualTo("RJCT");
        assertThat(transaction.getStsRsnInf()).singleElement()
                .satisfies(reason -> assertThat(reason.getRsn().getCd()).isEqualTo("AM04"));
    }

    private ResponseEntity<JsonNode> status(String authorization) {
        return http().get().uri("/v1/payments/{id}", ID).header(HttpHeaders.AUTHORIZATION, authorization)
                .retrieve().toEntity(JsonNode.class);
    }

    private ResponseEntity<JsonNode> pain002(String authorization) {
        return http().get().uri("/v1/payments/{id}/pain.002", ID).header(HttpHeaders.AUTHORIZATION, authorization)
                .accept(MediaType.APPLICATION_XML, MediaType.APPLICATION_PROBLEM_JSON)
                .retrieve().toEntity(JsonNode.class);
    }

    private static PaymentTransaction105 transaction(String xml) {
        return new IsoCodec().unmarshal(xml, Document.class).getCstmrPmtStsRpt().getOrgnlPmtInfAndSts().getFirst()
                .getTxInfAndSts().getFirst();
    }

    private static String mapping(String name) {
        try (var mapping = PaymentStatusIT.class.getResourceAsStream("/wiremock/payment-orchestrator/" + name
                + ".json")) {
            return new String(mapping.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().get("code").asString()).isEqualTo(code);
    }
}
