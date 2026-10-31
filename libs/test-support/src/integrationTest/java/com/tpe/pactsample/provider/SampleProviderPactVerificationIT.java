package com.tpe.pactsample.provider;

import au.com.dius.pact.provider.MessageAndMetadata;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.spring.junit5.PactVerificationSpringProvider;
import com.tpe.pactsample.provider.SampleProviderApplication.AccountOpened;
import com.tpe.testsupport.pact.PactVerification;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import tools.jackson.databind.json.JsonMapper;

/**
 * The sample provider verifies every collected pact that names it (specs/20-testing-strategy.md §10.3), HTTP
 * interactions against the running application, messages through {@link #accountOpened()}. Runs only with
 * {@code -Ppact-verify} and verifies this commit's pacts and the last release's ({@link PactVerification}). A sample,
 * not the pattern of §10.3 in full: its states write to an in-memory store instead of going through application
 * services, and the message is built here instead of through the outbox.
 */
@Provider("pact-sample-provider")
@PactVerification
@SpringBootTest(classes = SampleProviderApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SampleProviderPactVerificationIT {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    int port;

    @Autowired
    SampleProviderApplication.Accounts accounts;

    @BeforeEach
    void target(PactVerificationContext context) {
        accounts.clear();
        context.setTarget(context.getInteraction().isAsynchronousMessage()
                ? new MessageTestTarget()
                : new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationSpringProvider.class)
    void verify(PactVerificationContext context) {
        context.verifyInteraction();
    }

    @State("account is OPEN")
    void accountIsOpen(Map<String, Object> parameters) {
        accounts.open(UUID.fromString((String) parameters.get("accountId")));
    }

    @State("no account exists")
    void noAccountExists() {
        accounts.clear();
    }

    @PactVerifyProvider("an account opened event")
    MessageAndMetadata accountOpened() {
        AccountOpened event = new AccountOpened(UUID.fromString("0192f0e4-6b1d-7c3a-9a55-4c1f3b2e9d10"), "OPEN");
        return new MessageAndMetadata(JSON.writeValueAsBytes(event), SampleProviderApplication.eventHeaders());
    }
}
