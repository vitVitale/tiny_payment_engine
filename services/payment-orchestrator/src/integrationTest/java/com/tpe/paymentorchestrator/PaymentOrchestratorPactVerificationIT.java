package com.tpe.paymentorchestrator;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.spring.junit5.PactVerificationSpringProvider;
import com.tpe.testsupport.pact.PactVerification;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * payment-orchestrator verifies every collected pact that names it (specs/20-testing-strategy.md §10.3): P-01 of
 * payment-api, against the running service with the default bank directory. Every state gives the interaction a new
 * {@code commandId}, so repeated runs never meet each other's payments. Runs only with {@code -Ppact-verify}.
 */
@Provider("payment-orchestrator")
@PactVerification
class PaymentOrchestratorPactVerificationIT extends OrchestratorStack {

    @BeforeEach
    void target(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationSpringProvider.class)
    void PACT_P01_verify(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @State("no payment exists for the commandId")
    Map<String, Object> noPaymentExistsForTheCommandId() {
        return Map.of("commandId", UUID.randomUUID().toString());
    }

    /** The interaction's request, submitted once before: through the API, as payment-api did. */
    @State("a payment exists for the commandId")
    Map<String, Object> aPaymentExistsForTheCommandId(Map<String, Object> parameters) {
        String commandId = UUID.randomUUID().toString();
        submit(commandId, request(parameters));
        return Map.of("commandId", commandId);
    }

    /** The interaction's request with another amount, submitted before under its {@code commandId}. */
    @State("a payment for another request exists for the commandId")
    Map<String, Object> aPaymentForAnotherRequestExistsForTheCommandId(Map<String, Object> parameters) {
        String commandId = UUID.randomUUID().toString();
        Map<String, Object> other = request(parameters);
        other.put("amountMinor", ((Number) other.get("amountMinor")).longValue() + 1);
        submit(commandId, other);
        return Map.of("commandId", commandId);
    }

    /** deploy/config/bank-directory.yml has only the own bank and one peer; the interaction names another. */
    @State("the creditor's bank is not in the directory")
    Map<String, Object> theCreditorsBankIsNotInTheDirectory() {
        return noPaymentExistsForTheCommandId();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> request(Map<String, Object> parameters) {
        return new LinkedHashMap<>((Map<String, Object>) parameters.get("request"));
    }

    private void submit(String commandId, Map<String, Object> request) {
        request.put("commandId", commandId);
        ResponseEntity<String> response = http().post().uri("/v1/payments").contentType(MediaType.APPLICATION_JSON)
                .body(request).retrieve().toEntity(String.class);
        if (response.getStatusCode().value() != 201) {
            throw new IllegalStateException("the state's submit answered " + response.getStatusCode() + " "
                    + response.getBody());
        }
    }
}
