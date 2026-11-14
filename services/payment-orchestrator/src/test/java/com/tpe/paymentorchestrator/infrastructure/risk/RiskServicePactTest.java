package com.tpe.paymentorchestrator.infrastructure.risk;

import static au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody;
import static org.assertj.core.api.Assertions.assertThat;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.DslPart;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.dsl.PactDslWithState;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.tpe.contracts.ContractsModule;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.CommandId;
import com.tpe.paymentdomain.EndToEndId;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.Money;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import com.tpe.paymentorchestrator.application.risk.RiskAnswer;
import com.tpe.paymentorchestrator.domain.payment.PayeeVerification;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentDirection;
import com.tpe.paymentorchestrator.domain.payment.PaymentParty;
import com.tpe.paymentorchestrator.domain.payment.PaymentState;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * P-03, payment-orchestrator → risk-service {@code POST /v1/risk/assessments} (specs/20-testing-strategy.md §10.1):
 * every decision the orchestrator maps, both directions, through the real {@link HttpRiskAssessments}. Verified by
 * risk-service's {@code RiskServicePactVerificationIT} with {@code -Ppact-verify}.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = RiskServicePactTest.PROVIDER, pactVersion = PactSpecVersion.V4)
class RiskServicePactTest {

    static final String CONSUMER = "payment-orchestrator";
    static final String PROVIDER = "risk-service";

    private static final String RULES = "the seeded MVP rules apply";
    private static final String ASSESSED = "the payment is already assessed";
    private static final String IBAN = "[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}";
    private static final String BIC = "[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?";
    private static final String TX_ID = "[A-Za-z0-9]{1,35}";

    // one payer and payee per interaction, so the provider's velocity and new-payee rules see only that one
    private static final Example ALLOW = new Example("0192f0e4-7b1c-7cc3-a1b2-000000000001", 12_345, 1);
    private static final Example FRAUD = new Example("0192f0e4-7b1c-7cc3-a1b2-000000000002", 6_000_000, 2);
    private static final Example SANCTIONS = new Example("0192f0e4-7b1c-7cc3-a1b2-000000000003", 12_345, 3);
    private static final Example REVIEW = new Example("0192f0e4-7b1c-7cc3-a1b2-000000000004", 600_000, 4);
    private static final Example REPLAYED = new Example("0192f0e4-7b1c-7cc3-a1b2-000000000005", 12_345, 5);
    private static final Example INCOMING_ALLOW = new Example("0192f0e4-7b1c-7cc3-a1b2-000000000006", 12_345, 6);
    private static final Example INCOMING_FRAUD = new Example("0192f0e4-7b1c-7cc3-a1b2-000000000007", 6_000_000, 7);
    private static final Example INCOMING_SANCTIONS = new Example("0192f0e4-7b1c-7cc3-a1b2-000000000008", 12_345, 8);
    private static final Example INCOMING_REVIEW = new Example("0192f0e4-7b1c-7cc3-a1b2-000000000009", 12_345, 9);
    private static final Example INCOMING_REPLAYED = new Example("0192f0e4-7b1c-7cc3-a1b2-000000000010", 12_345, 10);

    @Pact(consumer = CONSUMER)
    V4Pact outgoingAllow(PactDslWithProvider builder) {
        return interaction(builder.given(RULES), "assess outgoing: allow", ALLOW, PaymentDirection.OUTGOING,
                "MATCH", decision("ALLOW", null));
    }

    @Pact(consumer = CONSUMER)
    V4Pact outgoingDenyByAFraudRule(PactDslWithProvider builder) {
        return interaction(builder.given(RULES), "assess outgoing: deny FR01", FRAUD, PaymentDirection.OUTGOING,
                "MATCH", decision("DENY", "FR01"));
    }

    /** Until the sanctions track imports the non-EU lists, risk-service answers {@code RR04} only as stored. */
    @Pact(consumer = CONSUMER)
    V4Pact outgoingDenyBySanctions(PactDslWithProvider builder) {
        return interaction(builder.given(ASSESSED, stored(SANCTIONS, "OUTGOING", "DENY", "RR04")),
                "assess outgoing: deny RR04", SANCTIONS, PaymentDirection.OUTGOING, "MATCH",
                decision("DENY", "RR04"));
    }

    @Pact(consumer = CONSUMER)
    V4Pact outgoingReview(PactDslWithProvider builder) {
        return interaction(builder.given(RULES), "assess outgoing: review", REVIEW, PaymentDirection.OUTGOING,
                "NO_MATCH", decision("REVIEW", null));
    }

    @Pact(consumer = CONSUMER)
    V4Pact outgoingReplayed(PactDslWithProvider builder) {
        return interaction(builder.given(ASSESSED, stored(REPLAYED, "OUTGOING", "DENY", "FR01")),
                "assess outgoing: a replayed assessmentId answers the stored decision", REPLAYED,
                PaymentDirection.OUTGOING, "MATCH", decision("DENY", "FR01"));
    }

    @Pact(consumer = CONSUMER)
    V4Pact incomingAllow(PactDslWithProvider builder) {
        return interaction(builder.given(RULES), "assess incoming: allow", INCOMING_ALLOW, PaymentDirection.INCOMING,
                null, decision("ALLOW", null));
    }

    @Pact(consumer = CONSUMER)
    V4Pact incomingDenyByAFraudRule(PactDslWithProvider builder) {
        return interaction(builder.given(RULES), "assess incoming: deny FR01", INCOMING_FRAUD,
                PaymentDirection.INCOMING, null, decision("DENY", "FR01"));
    }

    /** As outgoing: {@code RR04} only as stored until the non-EU lists exist. */
    @Pact(consumer = CONSUMER)
    V4Pact incomingDenyBySanctions(PactDslWithProvider builder) {
        return interaction(builder.given(ASSESSED, stored(INCOMING_SANCTIONS, "INCOMING", "DENY", "RR04")),
                "assess incoming: deny RR04", INCOMING_SANCTIONS, PaymentDirection.INCOMING, null,
                decision("DENY", "RR04"));
    }

    /** No seeded rule answers {@code REVIEW} for an incoming payment; a stored one stands for it. */
    @Pact(consumer = CONSUMER)
    V4Pact incomingReview(PactDslWithProvider builder) {
        return interaction(builder.given(ASSESSED, stored(INCOMING_REVIEW, "INCOMING", "REVIEW", "FR01")),
                "assess incoming: review", INCOMING_REVIEW, PaymentDirection.INCOMING, null,
                decision("REVIEW", null));
    }

    @Pact(consumer = CONSUMER)
    V4Pact incomingReplayed(PactDslWithProvider builder) {
        return interaction(builder.given(ASSESSED, stored(INCOMING_REPLAYED, "INCOMING", "DENY", "FR01")),
                "assess incoming: a replayed assessmentId answers the stored decision", INCOMING_REPLAYED,
                PaymentDirection.INCOMING, null, decision("DENY", "FR01"));
    }

    @Test
    @PactTestFor(pactMethod = "outgoingAllow")
    void PACT_P03_anAllowedPaymentIsAllowed(MockServer server) {
        assertThat(assess(server, ALLOW.payment(PaymentDirection.OUTGOING, PayeeVerification.MATCH)))
                .isEqualTo(new RiskAnswer.Allow());
    }

    @Test
    @PactTestFor(pactMethod = "outgoingDenyByAFraudRule")
    void PACT_P03_aFraudRuleDeniesWithFr01(MockServer server) {
        assertThat(assess(server, FRAUD.payment(PaymentDirection.OUTGOING, PayeeVerification.MATCH)))
                .isEqualTo(new RiskAnswer.Deny(ReasonCode.FR01));
    }

    @Test
    @PactTestFor(pactMethod = "outgoingDenyBySanctions")
    void PACT_P03_aSanctionsHitDeniesWithRr04(MockServer server) {
        assertThat(assess(server, SANCTIONS.payment(PaymentDirection.OUTGOING, PayeeVerification.MATCH)))
                .isEqualTo(new RiskAnswer.Deny(ReasonCode.RR04));
    }

    @Test
    @PactTestFor(pactMethod = "outgoingReview")
    void PACT_P03_aReviewIsAReview(MockServer server) {
        assertThat(assess(server, REVIEW.payment(PaymentDirection.OUTGOING, PayeeVerification.NO_MATCH)))
                .isEqualTo(new RiskAnswer.Review());
    }

    @Test
    @PactTestFor(pactMethod = "outgoingReplayed")
    void PACT_P03_aReplayedAssessmentAnswersTheStoredDecision(MockServer server) {
        assertThat(assess(server, REPLAYED.payment(PaymentDirection.OUTGOING, PayeeVerification.MATCH)))
                .isEqualTo(new RiskAnswer.Deny(ReasonCode.FR01));
    }

    @Test
    @PactTestFor(pactMethod = "incomingAllow")
    void PACT_P03_anAllowedIncomingPaymentIsAllowed(MockServer server) {
        assertThat(assess(server, INCOMING_ALLOW.payment(PaymentDirection.INCOMING, null)))
                .isEqualTo(new RiskAnswer.Allow());
    }

    @Test
    @PactTestFor(pactMethod = "incomingDenyByAFraudRule")
    void PACT_P03_aFraudRuleDeniesAnIncomingPayment(MockServer server) {
        assertThat(assess(server, INCOMING_FRAUD.payment(PaymentDirection.INCOMING, null)))
                .isEqualTo(new RiskAnswer.Deny(ReasonCode.FR01));
    }

    @Test
    @PactTestFor(pactMethod = "incomingDenyBySanctions")
    void PACT_P03_aSanctionsHitDeniesAnIncomingPaymentWithRr04(MockServer server) {
        assertThat(assess(server, INCOMING_SANCTIONS.payment(PaymentDirection.INCOMING, null)))
                .isEqualTo(new RiskAnswer.Deny(ReasonCode.RR04));
    }

    @Test
    @PactTestFor(pactMethod = "incomingReview")
    void PACT_P03_anIncomingReviewIsAReview(MockServer server) {
        assertThat(assess(server, INCOMING_REVIEW.payment(PaymentDirection.INCOMING, null)))
                .isEqualTo(new RiskAnswer.Review());
    }

    @Test
    @PactTestFor(pactMethod = "incomingReplayed")
    void PACT_P03_aReplayedIncomingAssessmentAnswersTheStoredDecision(MockServer server) {
        assertThat(assess(server, INCOMING_REPLAYED.payment(PaymentDirection.INCOMING, null)))
                .isEqualTo(new RiskAnswer.Deny(ReasonCode.FR01));
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    /** In the payment's own direction, as {@code ASSESS_RISK} asks. */
    private static RiskAnswer assess(MockServer server, Payment payment) {
        return client(server).assess(payment, payment.direction(), TIMEOUT);
    }

    private static HttpRiskAssessments client(MockServer server) {
        JsonMapper json = JsonMapper.builder().addModule(new ContractsModule()).build();
        return new HttpRiskAssessments(RestClient.builder()
                .baseUrl(server.getUrl())
                .configureMessageConverters(converters -> converters.withJsonConverter(
                        new JacksonJsonHttpMessageConverter(json)))
                .build(), Duration.ofSeconds(1));
    }

    private static V4Pact interaction(PactDslWithState given, String description, Example example,
            PaymentDirection direction, String vopResult, DslPart response) {
        return given.uponReceiving(description)
                .method("POST").path("/v1/risk/assessments")
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(newJsonBody(body -> {
                    body.stringMatcher("assessmentId", "[0-9a-f-]{36}:" + direction,
                            example.paymentId() + ":" + direction);
                    body.uuid("paymentId", UUID.fromString(example.paymentId()));
                    body.stringValue("direction", direction.name());
                    body.stringMatcher("txId", TX_ID, example.txId());
                    body.integerType("amountMinor", (int) example.amountMinor());
                    body.stringValue("currency", "EUR");
                    body.object("debtor", debtor -> {
                        debtor.stringType("name", "Anna Schmidt");
                        debtor.stringMatcher("iban", IBAN, example.debtorIban());
                    });
                    body.object("creditor", creditor -> {
                        creditor.stringType("name", "Max Mustermann");
                        creditor.stringMatcher("iban", IBAN, example.creditorIban());
                    });
                    body.stringMatcher("debtorAgentBic", BIC, "TPEBDEFFXXX");
                    body.stringMatcher("creditorAgentBic", BIC, "PEERDEFFXXX");
                    body.stringType("remittanceInformation", "Invoice 42");
                    if (vopResult == null) {
                        body.nullValue("vopResult");
                    } else {
                        body.stringValue("vopResult", vopResult);
                    }
                }).build())
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(response)
                .toPact(V4Pact.class);
    }

    /** Only what the orchestrator reads: the decision and, for a deny, its reason. */
    private static DslPart decision(String decision, String reasonCode) {
        return newJsonBody(body -> {
            body.stringValue("decision", decision);
            if (reasonCode != null) {
                body.stringValue("reasonCode", reasonCode);
            }
        }).build();
    }

    private static Map<String, Object> stored(Example example, String direction, String decision, String reason) {
        Map<String, Object> stored = new java.util.LinkedHashMap<>(Map.of("paymentId", example.paymentId(),
                "direction", direction, "decision", decision, "debtorIban", example.debtorIban(),
                "creditorIban", example.creditorIban(), "amountMinor", example.amountMinor()));
        if (reason != null) {
            stored.put("reasonCode", reason);
        }
        return stored;
    }

    /** An interaction's example payment; {@code n} picks its IBANs. */
    private record Example(String paymentId, long amountMinor, int n) {

        String txId() {
            return "TPE01JABCDEFGHJKMNPQRST000" + n;
        }

        String debtorIban() {
            return iban("10010010", 9_000_000_000L + n);
        }

        String creditorIban() {
            return iban("20020020", 9_100_000_000L + n);
        }

        Payment payment(PaymentDirection direction, PayeeVerification vopResult) {
            Instant accepted = Instant.parse("2026-11-13T10:00:00Z");
            return new Payment(new PaymentId(UUID.fromString(paymentId)), direction, PaymentState.RECEIVED, null,
                    false, direction == PaymentDirection.OUTGOING ? new CommandId(UUID.randomUUID()) : null,
                    "mobile-app", "0".repeat(64), new TxId(txId()),
                    new Uetr("6f1c2a3b-4d5e-4f60-8a1b-2c3d4e5f6a7b"), new EndToEndId("INV-2026-0042"),
                    new Money(amountMinor, java.util.Currency.getInstance("EUR")),
                    new PaymentParty(new PartyName("Anna Schmidt"), new Iban(debtorIban()), new Bic("TPEBDEFFXXX")),
                    new PaymentParty(new PartyName("Max Mustermann"), new Iban(creditorIban()),
                            new Bic("PEERDEFFXXX")),
                    new RemittanceInformation("Invoice 42"), "VOP-1", vopResult, accepted,
                    accepted.plusSeconds(10), accepted.plusSeconds(20), PendingAction.ASSESS_RISK, null, null, null,
                    null, null);
        }

        /** {@code DE} + mod 97-10 check digits + bank code + a 10-digit account number. */
        private static String iban(String bankCode, long account) {
            String bban = bankCode + String.format("%010d", account);
            String digits = bban + "131400";
            int remainder = 0;
            for (int i = 0; i < digits.length(); i++) {
                remainder = (remainder * 10 + (digits.charAt(i) - '0')) % 97;
            }
            return "DE" + String.format("%02d", 98 - remainder) + bban;
        }
    }
}
