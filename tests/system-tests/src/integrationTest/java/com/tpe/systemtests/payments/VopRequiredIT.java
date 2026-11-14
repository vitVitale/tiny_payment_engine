package com.tpe.systemtests.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.systemtests.harness.Bounds;
import com.tpe.systemtests.harness.PaymentSystemTest;
import com.tpe.systemtests.harness.Response;
import com.tpe.systemtests.harness.TestData;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;

/**
 * No payment is accepted without a valid VoP reference (specs/20-testing-strategy.md §4 E2E-O-17; UC-01 4a,
 * specs/10-payment-api.md §5.3): missing, expired or not covering the payee each is {@code 422 VOP_REQUIRED} with the
 * problem as {@code detail}, and the orchestrator never hears of the payment. The expired reference uses vop-service's
 * {@code systemtest} validity of 5 s (20 §2.1). No payment, so no webhook either (INV-13, with the notifications track).
 */
@EnabledIfSystemProperty(named = "tpe.local-stack", matches = "true",
        disabledReason = "needs the compose stack; run with -Dtpe.local-stack=true")
class VopRequiredIT extends PaymentSystemTest {

    @Test
    void E2E_O_17_aPaymentWithoutAReferenceIsVopRequired() throws SQLException {
        Party payer = customer(50_000);
        Party payee = customer(0);

        assertVopRequired(payer, payee.name(), payee, null, "MISSING");
    }

    @Test
    void E2E_O_17_aPaymentWithAnExpiredReferenceIsVopRequired() throws SQLException {
        Party payer = customer(50_000);
        Party payee = customer(0);
        JsonNode verification = verified(payer, payee.iban(), payee.name(), "MATCH");
        Instant expiresAt = Instant.parse(verification.get("expiresAt").asString());
        await().atMost(Bounds.referenceExpiry()).pollInterval(Duration.ofMillis(200))
                .until(() -> Instant.now().isAfter(expiresAt));

        assertVopRequired(payer, payee.name(), payee, verification.get("vopReference").asString(), "EXPIRED");
    }

    /** The reference was issued for the payee's name, the payment names someone else. */
    @Test
    void E2E_O_17_aPaymentToAnotherNameThanTheVerifiedOneIsVopRequired() throws SQLException {
        Party payer = customer(50_000);
        Party payee = customer(0);
        String reference = verified(payer, payee.iban(), payee.name(), "MATCH").get("vopReference").asString();

        assertVopRequired(payer, TestData.newCustomer().name(), payee, reference, "MISMATCH");
    }

    private void assertVopRequired(Party payer, String creditorName, Party payee, String reference, String problem)
            throws SQLException {
        String key = newIdempotencyKey();

        Response refused = initiate(paymentApi(), payer.token(),
                payment(payer, payee.iban(), creditorName, "123.45", reference), key);

        assertThat(refused.status()).as("%s", refused.body()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("VOP_REQUIRED");
        assertThat(refused.body().get("detail").asString()).isEqualTo(problem);
        assertThat(orchestratorPaymentsOf(key)).as("no payment").isEmpty();
        assertThat(available(payer)).isEqualTo(50_000);
    }
}
