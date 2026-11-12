package com.tpe.systemtests.harness;

import static au.com.dius.pact.consumer.dsl.LambdaDsl.newJsonBody;
import static org.assertj.core.api.Assertions.assertThat;

import au.com.dius.pact.consumer.MockServer;
import au.com.dius.pact.consumer.dsl.PactDslJsonBody;
import au.com.dius.pact.consumer.dsl.PactDslRequestWithPath;
import au.com.dius.pact.consumer.dsl.PactDslResponse;
import au.com.dius.pact.consumer.dsl.PactDslWithProvider;
import au.com.dius.pact.consumer.dsl.PactDslWithState;
import au.com.dius.pact.consumer.junit5.PactConsumerTestExt;
import au.com.dius.pact.consumer.junit5.PactTestFor;
import au.com.dius.pact.core.model.PactSpecVersion;
import au.com.dius.pact.core.model.V4Pact;
import au.com.dius.pact.core.model.annotations.Pact;
import com.tpe.contracts.AccountStatus;
import com.tpe.contracts.BlockReason;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * P-10, the ledger account calls of the harness (specs/20-testing-strategy.md §10.1, issue #63): {@link LedgerAccounts}
 * against the Pact mock server, one interaction per response the system tests rely on, verified by ledger-service with
 * {@code -Ppact-verify}. The account id comes from the provider state, as the ledger assigns it; the caller is a
 * back-office operator unless a {@code caller is …} state says otherwise, and the provider puts its own token of that
 * caller in {@code Authorization}.
 */
@ExtendWith(PactConsumerTestExt.class)
@PactTestFor(providerName = LedgerServicePactTest.PROVIDER, pactVersion = PactSpecVersion.V4)
class LedgerServicePactTest {

    static final String CONSUMER = "system-tests";
    static final String PROVIDER = "ledger-service";

    private static final UUID ACCOUNT = UUID.fromString("0192f0e4-6b1d-7c3a-9a55-4c1f3b2e9d10");
    private static final String IBAN = "DE89370400440532013000";
    private static final String IBAN_PATTERN = "DE[0-9]{20}";
    private static final String AUTHORIZATION = "Bearer token";
    private static final TestData.Customer CUSTOMER = new TestData.Customer("SYS-0192f0e4", "Test Anna Schmidt");

    @Pact(consumer = CONSUMER)
    V4Pact openAccount(PactDslWithProvider builder) {
        return posted(builder
                .uponReceiving("open account: created")
                .method("POST").path("/v1/accounts"))
                .body(newJsonBody(body -> {
                    body.stringType("customerId", CUSTOMER.customerId());
                    body.object("holder", holder -> {
                        holder.stringValue("type", "PERSON");
                        holder.stringType("name", CUSTOMER.name());
                        holder.stringValue("countryOfResidence", "DE");
                    });
                }).build())
                .willRespondWith()
                .status(201)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(newJsonBody(body -> {
                    body.uuid("accountId", ACCOUNT);
                    body.stringMatcher("iban", IBAN_PATTERN, IBAN);
                }).build())
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact fundAccount(PactDslWithProvider builder) {
        return ok(post(open(builder), "fund account: booked", "/funding")
                .body(newJsonBody(body -> {
                    body.integerType("amountMinor", 25_000);
                    body.stringValue("currency", "EUR");
                }).build()));
    }

    @Pact(consumer = CONSUMER)
    V4Pact readAccount(PactDslWithProvider builder) {
        return authorized(open(builder)
                .uponReceiving("read account: found")
                .method("GET").pathFromProviderState(accountPath(""), examplePath("")))
                .willRespondWith()
                .status(200)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .body(new PactDslJsonBody()
                        .stringValue("status", AccountStatus.OPEN.name())
                        .integerType("bookedBalanceMinor", 0))
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact blockByOperator(PactDslWithProvider builder) {
        return ok(post(open(builder), "block account: operator block", "/block").body(reason(BlockReason.OPERATOR)));
    }

    @Pact(consumer = CONSUMER)
    V4Pact unblockByOperator(PactDslWithProvider builder) {
        return ok(post(blocked(builder, BlockReason.OPERATOR), "unblock account: operator block lifted", "/unblock")
                .body(reason(BlockReason.OPERATOR)));
    }

    @Pact(consumer = CONSUMER)
    V4Pact sanctionsBlockByRiskService(PactDslWithProvider builder) {
        return ok(post(open(builder).given("caller is risk-service"), "block account: sanctions block by risk-service",
                "/block").body(reason(BlockReason.SANCTIONS)));
    }

    @Pact(consumer = CONSUMER)
    V4Pact sanctionsUnblockByOperator(PactDslWithProvider builder) {
        return post(blocked(builder, BlockReason.SANCTIONS), "unblock account: sanctions block protected from operator",
                "/unblock")
                .body(reason(BlockReason.SANCTIONS))
                .willRespondWith()
                .status(403)
                .matchHeader("Content-Type", "application/problem\\+json.*", "application/problem+json")
                .body(newJsonBody(body -> body.stringValue("code", "SANCTIONS_BLOCK_PROTECTED")).build())
                .toPact(V4Pact.class);
    }

    @Pact(consumer = CONSUMER)
    V4Pact sanctionsUnblockByCompliance(PactDslWithProvider builder) {
        return ok(post(blocked(builder, BlockReason.SANCTIONS).given("caller is a compliance officer"),
                "unblock account: sanctions block lifted by compliance", "/unblock")
                .body(reason(BlockReason.SANCTIONS)));
    }

    @Pact(consumer = CONSUMER)
    V4Pact closeEmptyAccount(PactDslWithProvider builder) {
        return okWithStatus(post(open(builder), "close account: closed", "/close").body("{}", "application/json"),
                AccountStatus.CLOSED);
    }

    @Pact(consumer = CONSUMER)
    V4Pact closeAccountWithBalance(PactDslWithProvider builder) {
        return post(builder.given("account is OPEN with booked balance", Map.of("bookedMinor", 25_000)),
                "close account: not empty", "/close")
                .body("{}", "application/json")
                .willRespondWith()
                .status(422)
                .matchHeader("Content-Type", "application/problem\\+json.*", "application/problem+json")
                .body(newJsonBody(body -> body.stringValue("code", "ACCOUNT_NOT_EMPTY")).build())
                .toPact(V4Pact.class);
    }

    @Test
    @PactTestFor(pactMethod = "openAccount")
    void PACT_P10_openAccount(MockServer server) {
        var opened = ledger(server).open(AUTHORIZATION, CUSTOMER);

        assertThat(opened.status()).isEqualTo(201);
        assertThat(opened.body().get("accountId").asString()).isEqualTo(ACCOUNT.toString());
        assertThat(opened.body().get("iban").asString()).isEqualTo(IBAN);
    }

    @Test
    @PactTestFor(pactMethod = "fundAccount")
    void PACT_P10_fundAccount(MockServer server) {
        assertThat(ledger(server).fund(AUTHORIZATION, ACCOUNT, 25_000).status()).isEqualTo(200);
    }

    @Test
    @PactTestFor(pactMethod = "readAccount")
    void PACT_P10_readAccount(MockServer server) {
        var account = ledger(server).account(AUTHORIZATION, ACCOUNT);

        assertThat(account.status()).isEqualTo(200);
        assertThat(account.body().get("status").asString()).isEqualTo(AccountStatus.OPEN.name());
        assertThat(account.body().get("bookedBalanceMinor").asLong()).isZero();
    }

    @Test
    @PactTestFor(pactMethod = "blockByOperator")
    void PACT_P10_blockByOperator(MockServer server) {
        assertThat(ledger(server).block(AUTHORIZATION, ACCOUNT, BlockReason.OPERATOR).status()).isEqualTo(200);
    }

    @Test
    @PactTestFor(pactMethod = "unblockByOperator")
    void PACT_P10_unblockByOperator(MockServer server) {
        assertThat(ledger(server).unblock(AUTHORIZATION, ACCOUNT, BlockReason.OPERATOR).status()).isEqualTo(200);
    }

    @Test
    @PactTestFor(pactMethod = "sanctionsBlockByRiskService")
    void PACT_P10_sanctionsBlockByRiskService(MockServer server) {
        assertThat(ledger(server).block(AUTHORIZATION, ACCOUNT, BlockReason.SANCTIONS).status()).isEqualTo(200);
    }

    @Test
    @PactTestFor(pactMethod = "sanctionsUnblockByOperator")
    void PACT_P10_sanctionsUnblockByOperator(MockServer server) {
        var refused = ledger(server).unblock(AUTHORIZATION, ACCOUNT, BlockReason.SANCTIONS);

        assertThat(refused.status()).isEqualTo(403);
        assertThat(refused.code()).isEqualTo("SANCTIONS_BLOCK_PROTECTED");
    }

    @Test
    @PactTestFor(pactMethod = "sanctionsUnblockByCompliance")
    void PACT_P10_sanctionsUnblockByCompliance(MockServer server) {
        assertThat(ledger(server).unblock(AUTHORIZATION, ACCOUNT, BlockReason.SANCTIONS).status()).isEqualTo(200);
    }

    @Test
    @PactTestFor(pactMethod = "closeEmptyAccount")
    void PACT_P10_closeEmptyAccount(MockServer server) {
        var closed = ledger(server).close(AUTHORIZATION, ACCOUNT);

        assertThat(closed.status()).isEqualTo(200);
        assertThat(closed.body().get("status").asString()).isEqualTo(AccountStatus.CLOSED.name());
    }

    @Test
    @PactTestFor(pactMethod = "closeAccountWithBalance")
    void PACT_P10_closeAccountWithBalance(MockServer server) {
        var refused = ledger(server).close(AUTHORIZATION, ACCOUNT);

        assertThat(refused.status()).isEqualTo(422);
        assertThat(refused.code()).isEqualTo("ACCOUNT_NOT_EMPTY");
    }

    private static LedgerAccounts ledger(MockServer server) {
        return new LedgerAccounts(URI.create(server.getUrl()));
    }

    private static PactDslWithState open(PactDslWithProvider builder) {
        return builder.given("account is OPEN");
    }

    private static PactDslWithState blocked(PactDslWithProvider builder, BlockReason reason) {
        return builder.given("account is BLOCKED", Map.of("reason", reason.name()));
    }

    /** {@code POST /v1/accounts/{id}<action>} of the state's account, as the harness sends it. */
    private static PactDslRequestWithPath post(PactDslWithState state, String description, String action) {
        return posted(state.uponReceiving(description).method("POST")
                .pathFromProviderState(accountPath(action), examplePath(action)));
    }

    /** The header every harness call carries. */
    private static PactDslRequestWithPath authorized(PactDslRequestWithPath request) {
        return request.matchHeader("Authorization", "Bearer .+", AUTHORIZATION);
    }

    /** The headers of a harness {@code POST}: {@code Authorization}, JSON and a fresh {@code Idempotency-Key}. */
    private static PactDslRequestWithPath posted(PactDslRequestWithPath request) {
        return authorized(request)
                .matchHeader("Content-Type", "application/json.*", "application/json")
                .matchHeader("Idempotency-Key", "systest-[0-9a-f-]{36}", "systest-" + ACCOUNT);
    }

    /** A JSON {@code 200} whose body the harness does not read. */
    private static V4Pact ok(PactDslRequestWithPath request) {
        return okResponse(request).toPact(V4Pact.class);
    }

    /** A JSON {@code 200} with the account {@code status} the harness reads. */
    private static V4Pact okWithStatus(PactDslRequestWithPath request, AccountStatus status) {
        return okResponse(request).body(new PactDslJsonBody().stringValue("status", status.name()))
                .toPact(V4Pact.class);
    }

    private static PactDslResponse okResponse(PactDslRequestWithPath request) {
        return request.willRespondWith()
                .status(200)
                .matchHeader("Content-Type", "application/json.*", "application/json");
    }

    private static PactDslJsonBody reason(BlockReason reason) {
        return new PactDslJsonBody().stringValue("reason", reason.name());
    }

    private static String accountPath(String action) {
        return "/v1/accounts/${accountId}" + action;
    }

    private static String examplePath(String action) {
        return "/v1/accounts/" + ACCOUNT + action;
    }
}
