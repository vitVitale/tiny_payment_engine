package com.tpe.paymentapi;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /v1/payee-verifications} (specs/10-payment-api.md §3.4, UC-02), against WireMock as vop-service with
 * the mappings P-02 checks.
 */
@ExtendWith(OutputCaptureExtension.class)
class PayeeVerificationIT extends PaymentApiStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String DEBTOR = "DE36100100100000000001";
    private static final String PAYEE = "DE63200200200000000001";
    private static final String VERIFY = "vop:verify";

    /** Unchanged but for the form of the instant, written with milliseconds as every instant of this API. */
    @Test
    void aVerificationIsForwardedAndItsAnswerReturnedUnchanged() {
        vopAnswers("verify-close-match");

        ResponseEntity<JsonNode> response = verify(Tokens.customer(VERIFY, DEBTOR), request(PAYEE));

        assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(201);
        assertThat(response.getBody()).isEqualTo(JSON.readTree("""
                {"vopReference":"VOP-01JABCDEFGHJKMNPQRSTVWXYZ0","result":"CLOSE_MATCH",
                 "actualName":"Anna Schmidt-Berg","expiresAt":"2026-11-14T10:30:00.000Z",
                 "respondedBy":"TPEBDEFFXXX"}"""));
        assertThat(VOP.findAll(anyRequestedFor(anyUrl()))).singleElement().satisfies((LoggedRequest asked) ->
                assertThat(JSON.readTree(asked.getBodyAsString())).isEqualTo(JSON.readTree("""
                        {"debtorIban":"%s","payeeIban":"%s","payeeName":"Max Mustermann",
                         "payeeOrganisationId":null}""".formatted(DEBTOR, PAYEE))));
    }

    /** API-AC-06, the verification half (UC-02 1a); the id stays pending until its XML half exists. */
    @Test
    void anInvalidPayeeIbanIsRefusedWithoutAskingVopService() {
        ResponseEntity<JsonNode> response = verify(Tokens.customer(VERIFY, DEBTOR),
                request("DE00200200200000000001"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().get("errors")).isEqualTo(JSON.readTree("""
                [{"field":"payeeIban","code":"INVALID_IBAN"}]"""));
        assertThat(VOP.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void aDebtorAccountNotInTheTokenIsForbidden() {
        ResponseEntity<JsonNode> response = verify(Tokens.customer(VERIFY, PAYEE), request(PAYEE));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody().get("code").asString()).isEqualTo("DEBTOR_ACCOUNT_NOT_OWNED");
        assertThat(VOP.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void theVerificationNeedsItsScope() {
        ResponseEntity<JsonNode> response = verify(Tokens.customer("payments:write", DEBTOR), request(PAYEE));

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(VOP.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void aPayeeBankOutsideTheDirectoryIsUnknown() {
        vopAnswers("verify-bank-unknown");

        ResponseEntity<JsonNode> response = verify(Tokens.customer(VERIFY, DEBTOR), request(PAYEE));

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getBody().get("code").asString()).isEqualTo("PAYEE_BANK_UNKNOWN");
    }

    /** vop-service down: nothing is retried, the payer may verify again. */
    @ParameterizedTest
    @ValueSource(strings = {"verify-unavailable", "verify-timeout"})
    void aVopServiceThatDoesNotAnswerIsUnavailable(String answer) {
        vopAnswers(answer);

        ResponseEntity<JsonNode> response = verify(Tokens.customer(VERIFY, DEBTOR), request(PAYEE));

        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody().get("code").asString()).isEqualTo("VOP_UNAVAILABLE");
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("1");
        assertThat(VOP.findAll(anyRequestedFor(anyUrl()))).hasSize(1);
    }

    @Test
    void theNamesAndIbansAreNeverLogged(CapturedOutput output) {
        vopAnswers("verify-close-match");
        verify(Tokens.customer(VERIFY, DEBTOR), request(PAYEE));
        verify(Tokens.customer(VERIFY, DEBTOR), request("DE00200200200000000001"));

        assertThat(output.getAll()).doesNotContain(DEBTOR, PAYEE, "DE00200200200000000001", "Max Mustermann");
    }

    private ResponseEntity<JsonNode> verify(String authorization, Map<String, Object> body) {
        return http().post().uri("/v1/payee-verifications").header(HttpHeaders.AUTHORIZATION, authorization)
                .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toEntity(JsonNode.class);
    }

    private static Map<String, Object> request(String payeeIban) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("debtorIban", DEBTOR);
        body.put("payeeIban", payeeIban);
        body.put("payeeName", "Max Mustermann");
        return body;
    }
}
