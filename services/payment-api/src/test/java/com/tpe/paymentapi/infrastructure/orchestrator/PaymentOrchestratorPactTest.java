package com.tpe.paymentapi.infrastructure.orchestrator;

import static au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody;
import static org.assertj.core.api.Assertions.assertThat;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.LambdaDslObject;
import au.com.dius.pact.consumer.dsl.PactDslResponse;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.dsl.PactDslWithState;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.tpe.contracts.ContractsModule;
import com.tpe.paymentdomain.Uetr;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.contracts.payment.PaymentView;
import com.tpe.contracts.Direction;
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
import com.tpe.paymentdomain.TxId;
import java.time.Instant;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * P-01, payment-api → payment-orchestrator {@code POST /v1/payments} (specs/20-testing-strategy.md §10.1): every
 * answer payment-api maps, through the real {@link HttpOrchestrator}. Verified by payment-orchestrator's
 * {@code PaymentOrchestratorPactVerificationIT} with {@code -Ppact-verify}. {@code GET /v1/payments/{id}} as the
 * idempotency guard's replay reads it (10 §5.1); the status query adds the interactions it maps.
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
    private static final String IN_A_STATE = "an outgoing payment is in a state";
    private static final List<String> PENDING_STATES = List.of("RECEIVED", "RISK_CHECKED", "FUNDS_RESERVED",
            "SENT_TO_CSM", "INVESTIGATING");
    private static final String TX_ID = "TPE01JABCDEFGHJKMNPQRSTVW0101";
    private static final String UETR = "eb6305c9-1f7f-49de-aed0-16487c27b42d";
    private static final String UETR_FORMAT = "[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}";
    private static final String FINALISED_AT = "2026-11-14T10:00:01.5Z";
    private static final String NO_PAYMENT = "no payment exists for the paymentId";

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

    /** Each state before the outcome (UC-07 3: {@code PDNG}), the provider's payment in it. */
    @Pact(consumer = CONSUMER)
    V4Pact pendingPayments(PactDslWithProvider builder) {
        PactDslResponse interactions = null;
        for (String state : PENDING_STATES) {
            PactDslResponse previous = interactions;
            interactions = status(parameters -> previous == null ? builder.given(IN_A_STATE, parameters)
                    : previous.given(IN_A_STATE, parameters), pendingId(state), state, null,
                    "status: a payment in " + state, state, false);
        }
        return interactions.toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact settledPayment(PactDslWithProvider builder) {
        return status(parameters -> builder.given(IN_A_STATE, parameters), "SETTLED", null,
                "status: a settled payment", "SETTLED", true).toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact rejectedPayment(PactDslWithProvider builder) {
        return status(parameters -> builder.given(IN_A_STATE, parameters), "REJECTED", "AM04",
                "status: a rejected payment with its reason", "REJECTED", true).toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact unknownPayment(PactDslWithProvider builder) {
        return builder.given(NO_PAYMENT)
                .uponReceiving("status: no payment of the id")
                .method("GET").pathFromProviderState(HttpOrchestrator.PAYMENTS + "/${paymentId}",
                        HttpOrchestrator.PAYMENTS + "/" + PAYMENT_ID)
                .willRespondWith()
                .status(404)
                .matchHeader("Content-Type", "application/problem\\+json.*", "application/problem+json")
                .body(newJsonBody(body -> body.stringValue("code", "PAYMENT_NOT_FOUND")).build())
                .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "pendingPayments")
    void PACT_P01_aPaymentBeforeItsOutcomeIsItsViewInEachState(MockServer server) {
        for (String state : PENDING_STATES) {
            String id = pendingId(state);
            assertThat(client(server).payment(new PaymentId(UUID.fromString(id))))
                    .isEqualTo(new Orchestrator.Found(view(id, PaymentState.valueOf(state), null, null)));
        }
    }

    @Test
    @PactTestFor(pactMethod = "settledPayment")
    void PACT_P01_aSettledPaymentIsFinalised(MockServer server) {
        assertThat(client(server).payment(new PaymentId(UUID.fromString(PAYMENT_ID))))
                .isEqualTo(new Orchestrator.Found(view(PaymentState.SETTLED, null, Instant.parse(FINALISED_AT))));
    }

    @Test
    @PactTestFor(pactMethod = "rejectedPayment")
    void PACT_P01_aRejectedPaymentHasItsReasonCode(MockServer server) {
        assertThat(client(server).payment(new PaymentId(UUID.fromString(PAYMENT_ID))))
                .isEqualTo(new Orchestrator.Found(view(PaymentState.REJECTED, ReasonCode.AM04,
                        Instant.parse(FINALISED_AT))));
    }

    @Test
    @PactTestFor(pactMethod = "unknownPayment")
    void PACT_P01_theStatusOfAnUnknownPaymentIsNotFound(MockServer server) {
        assertThat(client(server).payment(new PaymentId(UUID.fromString(PAYMENT_ID))))
                .isEqualTo(new Orchestrator.NotFound());
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

    /**
     * {@code GET /v1/payments/{id}} of the provider's payment in {@code state}: the submitted request, then the state
     * the provider puts it in; the whole view, as the status query shows it.
     */
    private static PactDslResponse status(Function<Map<String, Object>, PactDslWithState> given, String state,
            String reasonCode, String description, String states, boolean final_) {
        return status(given, PAYMENT_ID, state, reasonCode, description, states, final_);
    }

    /**
     * @param paymentId the example id; the provider's state gives the real one, so it only tells interactions with
     *     the same request apart in the consumer's mock server
     */
    private static PactDslResponse status(Function<Map<String, Object>, PactDslWithState> given, String paymentId,
            String state, String reasonCode, String description, String states, boolean final_) {
        Map<String, Object> parameters = new LinkedHashMap<>(submitted(CREDITOR));
        parameters.put("state", state);
        if (reasonCode != null) {
            parameters.put("reasonCode", reasonCode);
        }
        return given.apply(parameters)
                .uponReceiving(description)
                .method("GET").pathFromProviderState(HttpOrchestrator.PAYMENTS + "/${paymentId}",
                        HttpOrchestrator.PAYMENTS + "/" + paymentId)
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(newJsonBody(body -> {
                    body.stringMatcher("paymentId", UUID_FORMAT, paymentId);
                    body.stringValue("direction", "OUTGOING");
                    body.booleanType("onUs", false);
                    body.stringMatcher("state", states, state);
                    if (reasonCode == null) {
                        body.nullValue("reasonCode");
                    } else {
                        body.stringMatcher("reasonCode", "[A-Z0-9]{4}", reasonCode);
                    }
                    body.stringType("txId", TX_ID);
                    body.stringMatcher("uetr", UETR_FORMAT, UETR);
                    body.stringType("endToEndId", "INV-2026-0042");
                    body.integerType("amountMinor", 12_345);
                    body.stringValue("currency", "EUR");
                    body.object("debtor", debtor -> {
                        debtor.stringType("name", "Anna Schmidt");
                        debtor.stringMatcher("iban", IBAN, DEBTOR);
                    });
                    body.object("creditor", creditor -> {
                        creditor.stringType("name", "Max Mustermann");
                        creditor.stringMatcher("iban", IBAN, CREDITOR);
                    });
                    body.stringType("remittanceInformation", "Invoice 42");
                    body.stringMatcher("acceptedAt", INSTANT, ACCEPTED_AT);
                    if (final_) {
                        body.stringMatcher("finalisedAt", INSTANT, FINALISED_AT);
                    } else {
                        body.nullValue("finalisedAt");
                    }
                }).build());
    }

    /** {@code SENT_TO_CSM}, the state of the component tests' mapping, has the example id of every interaction. */
    private static String pendingId(String state) {
        return state.equals("SENT_TO_CSM") ? PAYMENT_ID
                : "0192f0e4-7b1c-7cc3-a1b2-00000000011" + PENDING_STATES.indexOf(state);
    }

    private static PaymentView view(PaymentState state, StatusReason reason, Instant finalisedAt) {
        return view(PAYMENT_ID, state, reason, finalisedAt);
    }

    private static PaymentView view(String paymentId, PaymentState state, StatusReason reason,
            Instant finalisedAt) {
        return new PaymentView(new PaymentId(UUID.fromString(paymentId)), Direction.OUTGOING, false, state, reason,
                new TxId(TX_ID), new Uetr(UETR), new EndToEndId("INV-2026-0042"),
                new Money(12_345, Currency.getInstance("EUR")),
                new PaymentView.Holder(new PartyName("Anna Schmidt"), new Iban(DEBTOR)),
                new PaymentView.Holder(new PartyName("Max Mustermann"), new Iban(CREDITOR)),
                new RemittanceInformation("Invoice 42"), Instant.parse(ACCEPTED_AT), finalisedAt);
    }

    private static HttpOrchestrator client(MockServer server) {
        JsonMapper json = JsonMapper.builder().addModule(new ContractsModule()).build();
        RestClient client = RestClient.builder()
                .baseUrl(server.getUrl())
                .configureMessageConverters(converters -> converters.withJsonConverter(
                        new JacksonJsonHttpMessageConverter(json)))
                .build();
        return new HttpOrchestrator(client, client);
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
