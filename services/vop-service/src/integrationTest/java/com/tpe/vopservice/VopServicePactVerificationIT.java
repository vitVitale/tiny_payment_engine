package com.tpe.vopservice;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.spring.junit5.PactVerificationSpringProvider;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.Iban;
import com.tpe.testsupport.pact.PactVerification;
import com.tpe.vopservice.application.accounts.AccountHolderProjection;
import com.tpe.vopservice.application.references.VopResults;
import com.tpe.vopservice.domain.accounts.AccountHolder;
import com.tpe.vopservice.domain.accounts.AccountHolder.AccountStatus;
import com.tpe.vopservice.domain.accounts.AccountHolder.HolderType;
import com.tpe.vopservice.domain.matching.VerificationResult;
import com.tpe.vopservice.domain.references.IssuedReference;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * vop-service verifies every collected pact that names it (specs/20-testing-strategy.md §10.3): P-02 of payment-api,
 * against the running service. A reference is stored as the requesting service stores the one it issues, with the
 * values the state names, so the check meets exactly the case of the interaction; a verification is of a new own-bank
 * IBAN, held by the state's holder or by nobody, so it is answered without a counterparty. Runs only with
 * {@code -Ppact-verify}.
 */
@Provider("vop-service")
@PactVerification
class VopServicePactVerificationIT extends VopStack {

    /** {@code tpe.vop.reference-validity} (specs/13-vop-service.md §7). */
    private static final Duration VALIDITY = Duration.ofMinutes(30);

    @Autowired
    VopResults results;

    @Autowired
    AccountHolderProjection projection;

    @BeforeEach
    void target(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationSpringProvider.class)
    void PACT_P02_verify(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @State("the reference is issued")
    void theReferenceIsIssued(Map<String, Object> reference) {
        store(reference, CLOCK.instant());
    }

    @State("the reference has expired")
    void theReferenceHasExpired(Map<String, Object> reference) {
        store(reference, CLOCK.instant().minus(VALIDITY).minusSeconds(1));
    }

    @State("no reference exists")
    void noReferenceExists() {}

    /** A new own-bank IBAN held by a person named as the interaction says; the state gives the IBAN. */
    @State("an own customer holds the payee IBAN")
    Map<String, Object> anOwnCustomerHoldsThePayeeIban(Map<String, Object> parameters) {
        var holder = new AccountHolder(UUID.randomUUID(), Ibans.own(), HolderType.PERSON,
                (String) parameters.get("holderName"), null, AccountStatus.OPEN, 1);
        projection.apply(UUID.randomUUID(), holder);
        return Map.of("payeeIban", holder.iban());
    }

    /** A new own-bank IBAN nobody holds: vop-service answers {@code NOT_POSSIBLE} itself. */
    @State("no customer holds the payee IBAN")
    Map<String, Object> noCustomerHoldsThePayeeIban() {
        return Map.of("payeeIban", Ibans.own());
    }

    /** deploy/config/bank-directory.yml has only the own bank and one peer; the interaction names another. */
    @State("the payee's bank is not in the directory")
    void thePayeesBankIsNotInTheDirectory() {}

    /** Replaces a reference an earlier verification run stored under the same id. */
    private void store(Map<String, Object> reference, Instant createdAt) {
        String vopReference = (String) reference.get("vopReference");
        results.deleteIfCreatedBefore(vopReference, CLOCK.instant().plus(Duration.ofDays(1)));
        VerificationResult result = VerificationResult.valueOf((String) reference.get("result"));
        String payeeName = (String) reference.get("payeeName");
        results.save(new IssuedReference(vopReference, new Iban((String) reference.get("debtorIban")),
                new Iban((String) reference.get("payeeIban")), payeeName, result,
                result == VerificationResult.CLOSE_MATCH ? payeeName + " GmbH" : null, new Bic("PEERDEFFXXX"),
                createdAt, createdAt.plus(VALIDITY)));
    }
}
