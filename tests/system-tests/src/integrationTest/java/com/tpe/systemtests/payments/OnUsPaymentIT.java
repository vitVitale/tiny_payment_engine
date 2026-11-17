package com.tpe.systemtests.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.ContractJson;
import com.tpe.contracts.PaymentSettled;
import com.tpe.systemtests.harness.Bounds;
import com.tpe.systemtests.harness.JsonHttp;
import com.tpe.systemtests.harness.PaymentSystemTest;
import com.tpe.systemtests.harness.Response;
import com.tpe.systemtests.harness.Scrape;
import com.tpe.systemtests.harness.TopicRecorder;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;

/**
 * Outgoing payments to our own customers (specs/20-testing-strategy.md §4, UC-01, UC-02 local, UC-03, UC-04 on-us
 * path, UC-07) through every service of M2: payee verification, initiation, settlement on-us and the status, as the
 * customer channel sees them and as the ledger books them.
 */
@EnabledIfSystemProperty(named = "tpe.local-stack", matches = "true",
        disabledReason = "needs the compose stack; run with -Dtpe.local-stack=true")
class OnUsPaymentIT extends PaymentSystemTest {

    private static final long FUNDED_MINOR = 50_000;
    private static final long AMOUNT_MINOR = 12_345;
    private static final String OUR_BIC = "TPEBDEFFXXX";
    /** {@code tpe.vop.requests} as Prometheus names its count (25 §11). */
    private static final String VOP_REQUESTS = "tpe_vop_requests_seconds_count";
    private static final Map<String, String> OWN_MATCH = Map.of("role", "REQUESTER", "route", "OWN",
            "result", "MATCH", "cause", "NONE");

    /**
     * The payee is our customer, so VoP is answered from our own accounts (route {@code OWN}, {@code cause} {@code NONE}:
     * an answer was given, 13 §8), and the payment settles on-us: one entry DR payer / CR payee, no {@code pacs.008},
     * one {@code PaymentSettled} with {@code onUs}. Webhooks to both (INV-13) come with the notifications track.
     */
    @Test
    @Tag("smoke")
    void E2E_O_02_aPaymentToOurOwnCustomerIsVerifiedLocallyAndSettledOnUs() {
        Party payer = customer(FUNDED_MINOR);
        Party payee = customer(0);

        // UC-02 local: once vop-service's projection has the payee, it matches without asking anyone
        double ownMatchesBefore = ownMatches();
        JsonNode verification = verified(payer, payee.iban(), payee.name(), "MATCH");
        assertThat(verification.get("respondedBy").asString()).isEqualTo(OUR_BIC);
        assertThat(ownMatches()).as("tpe.vop.requests{role=REQUESTER,route=OWN,result=MATCH,cause=NONE}")
                .isGreaterThan(ownMatchesBefore);

        Response initiated = initiate(payer.token(), Map.of(
                "endToEndId", "E2E-O-02",
                "debtor", Map.of("name", payer.name(), "iban", payer.iban()),
                "creditor", Map.of("name", payee.name(), "iban", payee.iban()),
                "amount", Map.of("value", "123.45", "currency", "EUR"),
                "remittanceInformation", "E2E-O-02 on-us",
                "vopReference", verification.get("vopReference").asString()));
        assertThat(initiated.status()).as("%s", initiated.body()).isEqualTo(202);
        UUID payment = UUID.fromString(initiated.body().get("paymentId").asString());
        String status = "/v1/payments/" + payment;

        // UC-07: what the customer sees, as JSON and as pain.002
        JsonNode settled = finalStatus(payer, payment);
        assertThat(settled.get("status").asString()).isEqualTo("ACSC");
        assertThat(settled.get("internalState").asString()).isEqualTo("SETTLED");
        assertThat(settled.has("reasonCode") && !settled.get("reasonCode").isNull()).isFalse();
        JsonHttp.Text pain002 = paymentApi().getText(payer.token(), status + "/pain.002", "application/xml");
        assertThat(pain002.status()).isEqualTo(200);
        assertThat(pain002.body()).contains("<GrpSts>ACSC</GrpSts>", "<TxSts>ACSC</TxSts>",
                "<OrgnlEndToEndId>E2E-O-02</OrgnlEndToEndId>");

        // one entry, DR payer / CR payee, of the amount
        assertThat(postings(payer.account(), payment)).containsExactly("ON_US DR " + AMOUNT_MINOR);
        assertThat(postings(payee.account(), payment)).containsExactly("ON_US CR " + AMOUNT_MINOR);
        assertThat(available(payer)).isEqualTo(FUNDED_MINOR - AMOUNT_MINOR);
        assertThat(booked(payee)).isEqualTo(AMOUNT_MINOR);

        // exactly one PaymentSettled, on-us; nothing towards the CSM
        await().atMost(Bounds.eventDelivery())
                .until(() -> !topics().records(TopicRecorder.PAYMENTS_EVENTS, payment.toString()).isEmpty());
        List<TopicRecorder.Recorded> events = topics().records(TopicRecorder.PAYMENTS_EVENTS, payment.toString());
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.type()).isEqualTo("PaymentSettled");
            assertThat(new ContractJson().read(event.value(), PaymentSettled.class).onUs()).isTrue();
        });
        assertThat(topics().mentioning(TopicRecorder.CSM_OUTBOUND_COMMANDS, payment.toString()))
                .as("no pacs.008").isEmpty();
    }

    /** The count of own-bank verifications vop-service answered {@code MATCH}, from its scrape. */
    private static double ownMatches() {
        return Scrape.value(vopService(), VOP_REQUESTS, OWN_MATCH);
    }
}
