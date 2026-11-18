package com.tpe.systemtests.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.systemtests.harness.JsonHttp;
import com.tpe.systemtests.harness.HostProxy;
import com.tpe.systemtests.harness.PaymentSystemTest;
import com.tpe.systemtests.harness.Response;
import com.tpe.systemtests.harness.ServiceJar;
import com.tpe.systemtests.harness.ServiceProcess;
import com.tpe.systemtests.harness.ServiceProfile;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * A retried initiation never creates a second payment (specs/20-testing-strategy.md §4 E2E-O-16; UC-01 2a, 5a, 6a): the
 * same {@code Idempotency-Key} is the same payment, whether payment-api answered the first time or lost the
 * orchestrator's answer. For the lost answer a second payment-api calls the orchestrator through Toxiproxy. The webhook
 * outcomes (INV-13) come with the notifications track, as for E2E-O-02.
 */
@EnabledIfSystemProperty(named = "tpe.local-stack", matches = "true",
        disabledReason = "needs the compose stack; run with -Dtpe.local-stack=true")
class IdempotentRetryIT extends PaymentSystemTest {

    /** payment-api's submit timeout as the harness runs it (10 §4: 1 s). */
    private static final Duration SUBMIT_TIMEOUT = ServiceProfile.systemtest(ServiceJar.PAYMENT_API)
            .duration("tpe.clients.orchestrator.timeout").orElseThrow();

    private HostProxy proxy;
    private JsonHttp proxied;

    /** A toxic left by a failed test would delay every later test's submits through the proxy. */
    @AfterEach
    void responsesPassAgain() {
        if (proxy != null) {
            proxy.heal();
        }
    }

    @Test
    void E2E_O_16_theSameKeySubmittedTwiceIsOnePayment() throws SQLException {
        Party payer = customer(50_000);
        Party payee = customer(0);
        var payment = payment(payer, payee.iban(), payee.name(), "123.45", reference(payer, payee));
        String key = newIdempotencyKey();

        Response first = initiate(paymentApi(), payer.token(), payment, key);
        Response again = initiate(paymentApi(), payer.token(), payment, key);

        assertThat(first.status()).as("%s", first.body()).isEqualTo(202);
        assertThat(again.status()).as("%s", again.body()).isEqualTo(202);
        UUID paymentId = UUID.fromString(first.body().get("paymentId").asString());
        assertThat(again.body().get("paymentId").asString()).isEqualTo(paymentId.toString());
        assertThat(orchestratorPaymentsOf(key)).containsExactly(paymentId);
        assertThat(finalStatus(payer, paymentId).get("status").asString()).isEqualTo("ACSC");
        assertThat(booked(payee)).as("paid once").isEqualTo(12_345);
    }

    /**
     * UC-01 5a, 6a: the orchestrator was reached and created the payment, but its answer came after payment-api's
     * timeout, so the client got {@code 503}; the retry with the key finds the payment the orchestrator made.
     */
    @Test
    void E2E_O_16_aSubmitWhoseAnswerWasLostIsThePaymentTheRetryGets() throws SQLException {
        // started first: the reference must still be valid at the retry (5 s in the systemtest profile)
        proxiedPaymentApi();
        Party payer = customer(50_000);
        Party payee = customer(0);
        var payment = payment(payer, payee.iban(), payee.name(), "123.45", reference(payer, payee));
        String key = newIdempotencyKey();
        proxy.delayResponses(SUBMIT_TIMEOUT.multipliedBy(2));

        Response lost = initiate(proxied, payer.token(), payment, key);

        assertThat(lost.status()).as("%s", lost.body()).isEqualTo(503);
        assertThat(lost.code()).isEqualTo("ORCHESTRATOR_UNAVAILABLE");
        assertThat(orchestratorPaymentsOf(key)).as("the orchestrator was reached").hasSize(1);

        proxy.heal();
        Response retried = initiate(proxied, payer.token(), payment, key);

        assertThat(retried.status()).as("%s", retried.body()).isEqualTo(202);
        UUID paymentId = UUID.fromString(retried.body().get("paymentId").asString());
        assertThat(orchestratorPaymentsOf(key)).containsExactly(paymentId);
        assertThat(finalStatus(payer, paymentId).get("status").asString()).isEqualTo("ACSC");
        assertThat(booked(payee)).as("paid once").isEqualTo(12_345);
    }

    /** A second payment-api calling the orchestrator through the proxy, beside the harness's own. */
    private void proxiedPaymentApi() {
        proxy = HostProxy.of("orchestrator", orchestratorService());
        proxied = new JsonHttp(ServiceProcess.of("payment-api-proxied", ServiceJar.PAYMENT_API, Map.of(
                "tpe.clients.orchestrator.base-url", proxy.uri().toString(),
                "tpe.clients.vop.base-url", vopService().toString())).baseUrl());
    }

    private String reference(Party payer, Party payee) {
        return verified(payer, payee.iban(), payee.name(), "MATCH").get("vopReference").asString();
    }
}
