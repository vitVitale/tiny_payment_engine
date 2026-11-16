package com.tpe.csmsimulator;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.spring.junit5.PactVerificationSpringProvider;
import com.tpe.testsupport.pact.PactVerification;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * csm-simulator verifies every collected pact that names it (specs/20-testing-strategy.md §10.3): P-06 of
 * csm-gateway, the CSM transport, against the running simulator. The interactions need no state: every message is
 * taken or refused on its envelope alone. Runs only with {@code -Ppact-verify}.
 */
@Provider("csm-simulator")
@PactVerification
class CsmSimulatorPactVerificationIT extends SimulatorStack {

    @BeforeEach
    void target(PactVerificationContext context) {
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationSpringProvider.class)
    void PACT_P06_verify(PactVerificationContext context) {
        context.verifyInteraction();
    }
}
