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
import java.time.Instant;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentapi.application.verification.PayeeVerifications;
import com.tpe.contracts.vop.VerificationResponse;
import com.tpe.contracts.vop.VerificationRequest;
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
 * P-02, payment-api → vop-service (specs/20-testing-strategy.md §10.1):
 * {@code POST /v1/verifications/{vopReference}/check}, valid and each problem, through the real {@link HttpReferenceChecks}; {@code POST /v1/verifications}, each result
 * and {@code 422 PAYEE_BANK_UNKNOWN}, through the real {@link HttpPayeeVerifications}. Verified by vop-service's
 * {@code VopServicePactVerificationIT} with {@code -Ppact-verify}.
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

    /** An IBAN of the own bank (10010010), which vop-service answers itself. */
    private static final String OWN_PAYEE = "DE36100100100000000777";
    /** A bank code the directory does not have (Commerzbank). */
    private static final String UNKNOWN_BANK = "DE89370400440532013000";
    private static final String HOLDER = "Anna Schmidt-Berg";
    private static final String HOLDS = "an own customer holds the payee IBAN";
    private static final String NO_HOLDER = "no customer holds the payee IBAN";
    private static final String BANK_UNKNOWN = "the payee's bank is not in the directory";
    private static final String VOP_REFERENCE = "VOP-[0-9A-HJKMNP-TV-Z]{26}";
    private static final String INSTANT = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d{1,9})?Z";
    private static final String EXPIRES_AT = "2026-11-14T10:30:00Z";
    private static final String REFERENCE = "VOP-01JABCDEFGHJKMNPQRSTVWXYZ0";

    @Pact(consumer = CONSUMER)
    V4Pact verifyMatch(PactDslWithProvider builder) {
        return verify(builder.given(HOLDS, Map.of("holderName", HOLDER)), "verify: the holder's name",
                HOLDER, "MATCH", null);
    }

    @Pact(consumer = CONSUMER)
    V4Pact verifyCloseMatch(PactDslWithProvider builder) {
        return verify(builder.given(HOLDS, Map.of("holderName", HOLDER)), "verify: a name close to the holder's",
                "Anna Schmitt", "CLOSE_MATCH", HOLDER);
    }

    @Pact(consumer = CONSUMER)
    V4Pact verifyNoMatch(PactDslWithProvider builder) {
        return verify(builder.given(HOLDS, Map.of("holderName", HOLDER)), "verify: another name than the holder's",
                "Peter Wagner", "NO_MATCH", null);
    }

    @Pact(consumer = CONSUMER)
    V4Pact verifyNotPossible(PactDslWithProvider builder) {
        return verify(builder.given(NO_HOLDER), "verify: an IBAN nobody holds", HOLDER, "NOT_POSSIBLE", null);
    }

    @Pact(consumer = CONSUMER)
    V4Pact verifyBankUnknown(PactDslWithProvider builder) {
        return builder.given(BANK_UNKNOWN)
                .uponReceiving("verify: the payee's bank is not in the directory")
                .method("POST").path(HttpPayeeVerifications.VERIFICATIONS)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(verification(UNKNOWN_BANK, HOLDER, false))
                .willRespondWith()
                .status(422)
                .matchHeader("Content-Type", "application/problem\\+json.*", "application/problem+json")
                .body(newJsonBody(body -> body.stringValue("code", "PAYEE_BANK_UNKNOWN")).build())
                .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "verifyMatch")
    void PACT_P02_theHoldersNameIsAMatch(MockServer server) {
        assertThat(verify(server, OWN_PAYEE, HOLDER)).isEqualTo(verified(VopResult.MATCH, null));
    }

    @Test
    @PactTestFor(pactMethod = "verifyCloseMatch")
    void PACT_P02_aCloseNameIsACloseMatchWithTheActualName(MockServer server) {
        assertThat(verify(server, OWN_PAYEE, "Anna Schmitt")).isEqualTo(verified(VopResult.CLOSE_MATCH, HOLDER));
    }

    @Test
    @PactTestFor(pactMethod = "verifyNoMatch")
    void PACT_P02_anotherNameIsNoMatch(MockServer server) {
        assertThat(verify(server, OWN_PAYEE, "Peter Wagner")).isEqualTo(verified(VopResult.NO_MATCH, null));
    }

    @Test
    @PactTestFor(pactMethod = "verifyNotPossible")
    void PACT_P02_anIbanNobodyHoldsIsNotPossible(MockServer server) {
        assertThat(verify(server, OWN_PAYEE, HOLDER)).isEqualTo(verified(VopResult.NOT_POSSIBLE, null));
    }

    @Test
    @PactTestFor(pactMethod = "verifyBankUnknown")
    void PACT_P02_aPayeeBankOutsideTheDirectoryIsUnknown(MockServer server) {
        assertThat(verify(server, UNKNOWN_BANK, HOLDER)).isEqualTo(new PayeeVerifications.PayeeBankUnknown());
    }

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
        return new HttpReferenceChecks(client(server))
                .check(reference, new Iban(DEBTOR), new Iban(PAYEE), new PartyName(PAYEE_NAME));
    }

    private static PayeeVerifications.Answer verify(MockServer server, String payeeIban, String payeeName) {
        return new HttpPayeeVerifications(client(server))
                .verify(new VerificationRequest(new Iban(DEBTOR), new Iban(payeeIban), payeeName, null));
    }

    private static RestClient client(MockServer server) {
        JsonMapper json = JsonMapper.builder().addModule(new ContractsModule()).build();
        return RestClient.builder()
                .baseUrl(server.getUrl())
                .configureMessageConverters(converters -> converters.withJsonConverter(
                        new JacksonJsonHttpMessageConverter(json)))
                .build();
    }

    private static PayeeVerifications.Verified verified(VopResult result, String actualName) {
        return new PayeeVerifications.Verified(new VerificationResponse(REFERENCE, result, actualName,
                Instant.parse(EXPIRES_AT), new Bic("TPEBDEFFXXX")));
    }

    /**
     * {@code POST /v1/verifications} for an IBAN of the own bank, as the provider's state gives it, so vop-service
     * answers from its own customers: no counterparty is involved.
     */
    private static V4Pact verify(PactDslWithState given, String description, String payeeName, String result,
            String actualName) {
        return given.uponReceiving(description)
                .method("POST").path(HttpPayeeVerifications.VERIFICATIONS)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(verification(OWN_PAYEE, payeeName, true))
                .willRespondWith()
                .status(201)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(newJsonBody(body -> {
                    body.stringMatcher("vopReference", VOP_REFERENCE, REFERENCE);
                    body.stringValue("result", result);
                    if (actualName == null) {
                        body.nullValue("actualName");
                    } else {
                        body.stringValue("actualName", actualName);
                    }
                    body.stringMatcher("expiresAt", INSTANT, EXPIRES_AT);
                    body.stringMatcher("respondedBy", "[A-Z0-9]{8}([A-Z0-9]{3})?", "TPEBDEFFXXX");
                }).build())
                .toPact(V4Pact.class);
    }

    private static DslPart verification(String payeeIban, String payeeName, boolean fromState) {
        return newJsonBody(body -> {
            body.stringMatcher("debtorIban", IBAN, DEBTOR);
            if (fromState) {
                body.valueFromProviderState("payeeIban", "${payeeIban}", payeeIban);
            } else {
                body.stringMatcher("payeeIban", IBAN, payeeIban);
            }
            body.stringValue("payeeName", payeeName);
            body.nullValue("payeeOrganisationId");
        }).build();
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
