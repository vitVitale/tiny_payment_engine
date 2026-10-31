package com.tpe.pactsample.consumer;

import static au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody;
import static org.assertj.core.api.Assertions.assertThat;

import au.com.dius.pact.consumer.MessagePactBuilder;
import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.consumer.junit5.ProviderType;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Interaction;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.tpe.pactsample.consumer.SampleAccounts.Account;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * The sample consumer's pact with the sample provider (issue #20): HTTP and message interactions, written to
 * {@code target/pacts} and verified by {@code SampleProviderPactVerificationIT} with {@code -Ppact-verify}. It proves
 * the build chain only: the message side reads with a stand-in mapping instead of the real listener mapping and
 * {@code ContractValidator} of specs/20-testing-strategy.md §10.2. Replaced by the real pacts once P-04 lands.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = SampleProviderPactTest.PROVIDER, pactVersion = PactSpecVersion.V4)
class SampleProviderPactTest {

    static final String CONSUMER = "pact-sample-consumer";
    static final String PROVIDER = "pact-sample-provider";

    private static final UUID ACCOUNT = UUID.fromString("0192f0e4-6b1d-7c3a-9a55-4c1f3b2e9d10");

    @Pact(consumer = CONSUMER)
    V4Pact findAnOpenAccount(PactDslWithProvider builder) {
        return builder
                .given("account is OPEN", Map.of("accountId", ACCOUNT.toString()))
                .uponReceiving("find account: open")
                .method("GET").path("/sample/accounts/" + ACCOUNT)
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(newJsonBody(body -> {
                    body.uuid("id", ACCOUNT);
                    body.stringValue("status", "OPEN");
                }).build())
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact findAnUnknownAccount(PactDslWithProvider builder) {
        return builder
                .given("no account exists")
                .uponReceiving("find account: not found")
                .method("GET").path("/sample/accounts/" + ACCOUNT)
                .willRespondWith()
                .status(404)
                .matchHeader("Content-Type", "application/problem\\+json.*", "application/problem+json")
                .body(newJsonBody(body -> body.stringValue("code", "ACCOUNT_NOT_FOUND")).build())
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact accountOpened(MessagePactBuilder builder) {
        return builder
                .expectsToReceive("an account opened event")
                .withMetadata(Map.of("tpe-message-type", "AccountOpened", "tpe-schema-version", "1"))
                .withContent(newJsonBody(body -> {
                    body.uuid("accountId", ACCOUNT);
                    body.stringValue("status", "OPEN");
                }).build())
                .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "findAnOpenAccount")
    void anOpenAccountIsFound(MockServer server) {
        assertThat(new SampleAccounts(server.getUrl()).find(ACCOUNT)).contains(new Account(ACCOUNT, "OPEN"));
    }

    @Test
    @PactTestFor(pactMethod = "findAnUnknownAccount")
    void anUnknownAccountIsEmpty(MockServer server) {
        assertThat(new SampleAccounts(server.getUrl()).find(ACCOUNT)).isEmpty();
    }

    @Test
    @PactTestFor(pactMethod = "accountOpened", providerType = ProviderType.ASYNCH)
    void anAccountOpenedEventIsRead(V4Interaction.AsynchronousMessage message) {
        var event = SampleAccountEvents.read(message.getContents().getContents().getValue(),
                message.getContents().getMetadata());

        assertThat(event).isEqualTo(new SampleAccountEvents.AccountOpened(ACCOUNT, "OPEN"));
    }
}
