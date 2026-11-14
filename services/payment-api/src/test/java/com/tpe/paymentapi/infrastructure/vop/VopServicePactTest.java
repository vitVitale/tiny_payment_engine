package com.tpe.paymentapi.infrastructure.vop;

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
import com.tpe.contracts.vop.VopReferenceProblem;
import com.tpe.contracts.vop.VopResult;
import com.tpe.paymentapi.application.submission.ReferenceChecks;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.PartyName;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * P-02, payment-api → vop-service {@code POST /v1/verifications/{vopReference}/check}
 * (specs/20-testing-strategy.md §10.1): valid, and each problem, through the real {@link HttpReferenceChecks}.
 * Verified by vop-service's {@code VopServicePactVerificationIT} with {@code -Ppact-verify}; the verify interactions
 * come with the payee verification proxy.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = VopServicePactTest.PROVIDER, pactVersion = PactSpecVersion.V4)
class VopServicePactTest {

    static final String CONSUMER = "payment-api";
    static final String PROVIDER = "vop-service";

    private static final String IBAN = "[A-Z]{2}[0-9]{2}[A-Z0-9]{11,30}";
    private static final String DEBTOR = "DE36100100100000000001";
    private static final String PAYEE = "DE63200200200000000001";
    private static final String PAYEE_NAME = "Max Mustermann";

    // one reference per interaction, so the provider's states do not meet
    private static final String VALID = "VOP-01JABCDEFGHJKMNPQRSTVW0001";
    private static final String EXPIRED = "VOP-01JABCDEFGHJKMNPQRSTVW0002";
    private static final String MISMATCH = "VOP-01JABCDEFGHJKMNPQRSTVW0003";
    private static final String UNKNOWN = "VOP-01JABCDEFGHJKMNPQRSTVW0004";

    private static final String ISSUED = "the reference is issued";
    private static final String HAS_EXPIRED = "the reference has expired";
    private static final String NONE = "no reference exists";

    @Pact(consumer = CONSUMER)
    V4Pact valid(PactDslWithProvider builder) {
        return check(builder.given(ISSUED, issued(VALID, PAYEE_NAME, "CLOSE_MATCH")),
                "check: the reference covers the payment", VALID,
                newJsonBody(body -> {
                    body.booleanValue("valid", true);
                    body.stringValue("result", "CLOSE_MATCH");
                }).build());
    }

    @Pact(consumer = CONSUMER)
    V4Pact expired(PactDslWithProvider builder) {
        return check(builder.given(HAS_EXPIRED, issued(EXPIRED, PAYEE_NAME, "MATCH")),
                "check: the reference has expired", EXPIRED, invalid("EXPIRED"));
    }

    /** Issued for another payee name than the payment's. */
    @Pact(consumer = CONSUMER)
    V4Pact mismatch(PactDslWithProvider builder) {
        return check(builder.given(ISSUED, issued(MISMATCH, "Erika Mustermann", "MATCH")),
                "check: the reference was issued for another payment", MISMATCH, invalid("MISMATCH"));
    }

    @Pact(consumer = CONSUMER)
    V4Pact unknown(PactDslWithProvider builder) {
        return check(builder.given(NONE), "check: the reference is unknown", UNKNOWN, invalid("UNKNOWN"));
    }

    @Test
    @PactTestFor(pactMethod = "valid")
    void PACT_P02_aReferenceCoveringThePaymentIsValidWithItsResult(MockServer server) {
        assertThat(check(server, VALID)).isEqualTo(new ReferenceChecks.Valid(VopResult.CLOSE_MATCH));
    }

    @Test
    @PactTestFor(pactMethod = "expired")
    void PACT_P02_anExpiredReferenceIsInvalid(MockServer server) {
        assertThat(check(server, EXPIRED)).isEqualTo(new ReferenceChecks.Invalid(VopReferenceProblem.EXPIRED));
    }

    @Test
    @PactTestFor(pactMethod = "mismatch")
    void PACT_P02_aReferenceOfAnotherPaymentIsInvalid(MockServer server) {
        assertThat(check(server, MISMATCH)).isEqualTo(new ReferenceChecks.Invalid(VopReferenceProblem.MISMATCH));
    }

    @Test
    @PactTestFor(pactMethod = "unknown")
    void PACT_P02_anUnknownReferenceIsInvalid(MockServer server) {
        assertThat(check(server, UNKNOWN)).isEqualTo(new ReferenceChecks.Invalid(VopReferenceProblem.UNKNOWN));
    }

    private static ReferenceChecks.Answer check(MockServer server, String reference) {
        JsonMapper json = JsonMapper.builder().addModule(new ContractsModule()).build();
        HttpReferenceChecks client = new HttpReferenceChecks(RestClient.builder()
                .baseUrl(server.getUrl())
                .configureMessageConverters(converters -> converters.withJsonConverter(
                        new JacksonJsonHttpMessageConverter(json)))
                .build());
        return client.check(reference, new Iban(DEBTOR), new Iban(PAYEE), new PartyName(PAYEE_NAME));
    }

    private static V4Pact check(PactDslWithState given, String description, String reference, DslPart response) {
        return given.uponReceiving(description)
                .method("POST").path("/v1/verifications/" + reference + "/check")
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(newJsonBody(body -> {
                    body.stringMatcher("debtorIban", IBAN, DEBTOR);
                    body.stringMatcher("payeeIban", IBAN, PAYEE);
                    body.stringType("payeeName", PAYEE_NAME);
                }).build())
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(response)
                .toPact(V4Pact.class);
    }

    /** Only what payment-api reads: {@code valid} and the problem. */
    private static DslPart invalid(String problem) {
        return newJsonBody(body -> {
            body.booleanValue("valid", false);
            body.stringValue("problem", problem);
        }).build();
    }

    /** What the provider stores: the reference, the payment it was issued for and the verification's result. */
    private static Map<String, Object> issued(String reference, String payeeName, String result) {
        return Map.of("vopReference", reference, "debtorIban", DEBTOR, "payeeIban", PAYEE, "payeeName", payeeName,
                "result", result);
    }
}
