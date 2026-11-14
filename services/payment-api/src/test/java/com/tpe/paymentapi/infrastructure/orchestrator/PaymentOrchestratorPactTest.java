package com.tpe.paymentapi.infrastructure.orchestrator;

import static au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody;
import static org.assertj.core.api.Assertions.assertThat;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.LambdaDslObject;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.tpe.contracts.ContractsModule;
import com.tpe.contracts.payment.PaymentAccepted;
import com.tpe.contracts.payment.PaymentState;
import com.tpe.contracts.payment.SubmitPayment;
import com.tpe.contracts.vop.VopResult;
import com.tpe.paymentapi.application.submission.Orchestrator;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.RemittanceInformation;
import java.time.Instant;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * P-01, payment-api → payment-orchestrator {@code POST /v1/payments} (specs/20-testing-strategy.md §10.1): every
 * answer payment-api maps, through the real {@link HttpOrchestrator}. Verified by payment-orchestrator's
 * {@code PaymentOrchestratorPactVerificationIT} with {@code -Ppact-verify}; the status interactions come with the
 * status query.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = PaymentOrchestratorPactTest.PROVIDER, pactVersion = PactSpecVersion.V4)
class PaymentOrchestratorPactTest {

    static final String CONSUMER = "payment-api";
    static final String PROVIDER = "payment-orchestrator";

    private static final String IBAN = "[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}";
    private static final String UUID_FORMAT = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z";
    private static final String STATES = "RECEIVED|RISK_CHECKED|FUNDS_RESERVED|SENT_TO_CSM|INVESTIGATING|SETTLED"
            + "|REJECTED";

    private static final String COMMAND_ID = "5c1e9a3d-7f2b-5a4c-8d6e-0f1a2b3c4d5e";
    private static final String PAYMENT_ID = "0192f0e4-7b1c-7cc3-a1b2-000000000101";
    private static final String ACCEPTED_AT = "2026-11-14T10:00:00.123Z";
    private static final String DEBTOR = "DE36100100100000000001";
    private static final String CREDITOR = "DE63200200200000000001";
    /** A bank code the directory does not have (Commerzbank). */
    private static final String UNKNOWN_BANK = "DE89370400440532013000";

    private static final String NEW = "no payment exists for the commandId";
    private static final String EXISTS = "a payment exists for the commandId";
    private static final String CONFLICT = "a payment for another request exists for the commandId";
    private static final String NOT_IN_DIRECTORY = "the creditor's bank is not in the directory";

    @Pact(consumer = CONSUMER)
    V4Pact newPayment(PactDslWithProvider builder) {
        return builder.given(NEW)
                .uponReceiving("submit: a new payment")
                .method("POST").path(HttpOrchestrator.PAYMENTS)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(submit(CREDITOR))
                .willRespondWith()
                .status(201)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(accepted("RECEIVED"))
                .toPact(V4Pact.class);
    }

    /** The earlier attempt reached the orchestrator, its answer was lost (UC-01 6a). */
    @Pact(consumer = CONSUMER)
    V4Pact existingPayment(PactDslWithProvider builder) {
        return builder.given(EXISTS, submitted(CREDITOR))
                .uponReceiving("submit: the payment of an earlier submit")
                .method("POST").path(HttpOrchestrator.PAYMENTS)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(submit(CREDITOR))
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(accepted(STATES))
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact commandConflict(PactDslWithProvider builder) {
        return builder.given(CONFLICT, submitted(CREDITOR))
                .uponReceiving("submit: the commandId has a payment for another request")
                .method("POST").path(HttpOrchestrator.PAYMENTS)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(submit(CREDITOR))
                .willRespondWith()
                .status(409)
                .matchHeader("Content-Type", "application/problem\\+json.*", "application/problem+json")
                .body(newJsonBody(body -> body.stringValue("code", "COMMAND_CONFLICT")).build())
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact creditorAgentNotReachable(PactDslWithProvider builder) {
        return builder.given(NOT_IN_DIRECTORY)
                .uponReceiving("submit: the creditor's bank is not reachable")
                .method("POST").path(HttpOrchestrator.PAYMENTS)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(submit(UNKNOWN_BANK))
                .willRespondWith()
                .status(422)
                .matchHeader("Content-Type", "application/problem\\+json.*", "application/problem+json")
                .body(newJsonBody(body -> body.stringValue("code", "CREDITOR_AGENT_NOT_REACHABLE")).build())
                .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "newPayment")
    void PACT_P01_aNewPaymentIsCreated(MockServer server) {
        assertThat(client(server).submit(command(CREDITOR))).isEqualTo(new Orchestrator.Accepted(
                new PaymentAccepted(new PaymentId(UUID.fromString(PAYMENT_ID)), PaymentState.RECEIVED, null,
                        Instant.parse(ACCEPTED_AT)), true));
    }

    @Test
    @PactTestFor(pactMethod = "existingPayment")
    void PACT_P01_theCommandIdOfAnEarlierSubmitAnswersItsPayment(MockServer server) {
        assertThat(client(server).submit(command(CREDITOR))).isEqualTo(new Orchestrator.Accepted(
                new PaymentAccepted(new PaymentId(UUID.fromString(PAYMENT_ID)), PaymentState.RECEIVED, null,
                        Instant.parse(ACCEPTED_AT)), false));
    }

    @Test
    @PactTestFor(pactMethod = "commandConflict")
    void PACT_P01_aCommandIdOfAnotherRequestConflicts(MockServer server) {
        assertThat(client(server).submit(command(CREDITOR))).isEqualTo(new Orchestrator.CommandConflict());
    }

    @Test
    @PactTestFor(pactMethod = "creditorAgentNotReachable")
    void PACT_P01_aCreditorBankOutsideTheDirectoryIsNotReachable(MockServer server) {
        assertThat(client(server).submit(command(UNKNOWN_BANK)))
                .isEqualTo(new Orchestrator.CreditorAgentNotReachable());
    }

    private static HttpOrchestrator client(MockServer server) {
        JsonMapper json = JsonMapper.builder().addModule(new ContractsModule()).build();
        return new HttpOrchestrator(RestClient.builder()
                .baseUrl(server.getUrl())
                .configureMessageConverters(converters -> converters.withJsonConverter(
                        new JacksonJsonHttpMessageConverter(json)))
                .build());
    }

    private static SubmitPayment command(String creditorIban) {
        return new SubmitPayment(UUID.fromString(COMMAND_ID), "mobile-app", new EndToEndId("INV-2026-0042"),
                new SubmitPayment.Debtor(new PartyName("Anna Schmidt"), new Iban(DEBTOR)),
                new SubmitPayment.Creditor(new PartyName("Max Mustermann"), new Iban(creditorIban), null),
                new Money(12_345, Currency.getInstance("EUR")), new RemittanceInformation("Invoice 42"),
                "VOP-01JABCDEFGHJKMNPQRSTVWXYZ0", VopResult.MATCH);
    }

    /**
     * The request as {@link #command} writes it; the provider state gives the {@code commandId}, so every
     * verification run has its own payment.
     */
    private static DslPart submit(String creditorIban) {
        return newJsonBody(body -> {
            body.valueFromProviderState("commandId", "${commandId}", COMMAND_ID);
            fields(body, creditorIban);
        }).build();
    }

    private static void fields(LambdaDslObject body, String creditorIban) {
        body.stringType("clientId", "mobile-app");
        body.stringType("endToEndId", "INV-2026-0042");
        body.object("debtor", debtor -> {
            debtor.stringType("name", "Anna Schmidt");
            debtor.stringMatcher("iban", IBAN, DEBTOR);
        });
        body.object("creditor", creditor -> {
            creditor.stringType("name", "Max Mustermann");
            creditor.stringMatcher("iban", IBAN, creditorIban);
        });
        body.integerType("amountMinor", 12_345);
        body.stringValue("currency", "EUR");
        body.stringType("remittanceInformation", "Invoice 42");
        body.stringType("vopReference", "VOP-01JABCDEFGHJKMNPQRSTVWXYZ0");
        body.stringValue("vopResult", "MATCH");
    }

    /** The request of the interaction but its {@code commandId}, for the provider to submit first. */
    private static Map<String, Object> submitted(String creditorIban) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("clientId", "mobile-app");
        request.put("endToEndId", "INV-2026-0042");
        request.put("debtor", Map.of("name", "Anna Schmidt", "iban", DEBTOR));
        request.put("creditor", Map.of("name", "Max Mustermann", "iban", creditorIban));
        request.put("amountMinor", 12_345);
        request.put("currency", "EUR");
        request.put("remittanceInformation", "Invoice 42");
        request.put("vopReference", "VOP-01JABCDEFGHJKMNPQRSTVWXYZ0");
        request.put("vopResult", "MATCH");
        return Map.of("request", request);
    }

    /** Only what payment-api reads. */
    private static DslPart accepted(String states) {
        return newJsonBody(body -> {
            body.stringMatcher("paymentId", UUID_FORMAT, PAYMENT_ID);
            body.stringMatcher("state", states, "RECEIVED");
            body.stringMatcher("acceptedAt", INSTANT, ACCEPTED_AT);
        }).build();
    }
}
