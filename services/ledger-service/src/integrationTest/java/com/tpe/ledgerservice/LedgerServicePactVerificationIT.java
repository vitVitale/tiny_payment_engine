package com.tpe.ledgerservice;

import au.com.dius.pact.provider.MessageAndMetadata;
import au.com.dius.pact.provider.PactVerifyProvider;
import au.com.dius.pact.provider.ProviderResponse;
import au.com.dius.pact.provider.junit5.HttpTestTarget;
import au.com.dius.pact.provider.junit5.MessageTestTarget;
import au.com.dius.pact.provider.junit5.PactVerificationContext;
import au.com.dius.pact.provider.junitsupport.Provider;
import au.com.dius.pact.provider.junitsupport.State;
import au.com.dius.pact.provider.spring.junit5.PactVerificationSpringProvider;
import com.tpe.ledgerservice.application.account.AccountService;
import com.tpe.ledgerservice.application.account.Caller;
import com.tpe.ledgerservice.application.account.OpenAccount;
import com.tpe.ledgerservice.application.posting.FundingService;
import com.tpe.ledgerservice.domain.account.Account;
import com.tpe.ledgerservice.domain.account.BlockReason;
import com.tpe.ledgerservice.domain.account.CustomerId;
import com.tpe.ledgerservice.domain.account.Holder;
import com.tpe.ledgerservice.domain.account.HolderType;
import com.tpe.testsupport.pact.PactVerification;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.apache.hc.core5.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * ledger-service verifies every collected pact that names it (specs/20-testing-strategy.md §10.3): P-10, the account
 * calls of the system-test harness, and M-04, the {@code accounts.events} snapshots its projections read. Runs only
 * with {@code -Ppact-verify}, against this commit's pacts and the last release's ({@link PactVerification}).
 *
 * <p>States seed accounts through the application services and hand the new account's id to the request path. The
 * pact's {@code Authorization} is the consumer's example: each request carries a token of this JVM's key instead, of a
 * back-office operator unless a {@code caller is …} state names another caller. Each request also gets a fresh
 * {@code Idempotency-Key}, so the example key of one interaction never replays another's response.
 *
 * <p>A message is produced through the real outbox path: the change goes through the application service, and the
 * verified bytes are the payload and headers of the outbox row the relay publishes.
 */
@Provider("ledger-service")
@PactVerification
class LedgerServicePactVerificationIT extends LedgerStack {

    private static final String STATE_ACTOR = "pact-state";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    AccountService accounts;

    @Autowired
    FundingService funding;

    @Autowired
    JdbcClient jdbc;

    private String caller;

    @BeforeEach
    void target(PactVerificationContext context) {
        caller = operator("pact-operator");
        context.setTarget(context.getInteraction().isAsynchronousMessage()
                ? new MessageTestTarget()
                : new CallerTarget(port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationSpringProvider.class)
    void PACT_P10_PACT_M04_verify(PactVerificationContext context) {
        context.verifyInteraction();
    }

    /** The service on {@code port}, called with the state's caller and a fresh {@code Idempotency-Key}. */
    private final class CallerTarget extends HttpTestTarget {

        CallerTarget(int port) {
            super("localhost", port);
        }

        @Override
        public ProviderResponse executeInteraction(Object client, Object request) {
            var http = (HttpRequest) request;
            if (http.containsHeader(AUTHORIZATION)) {
                http.setHeader(AUTHORIZATION, caller);
            }
            if (http.containsHeader(IDEMPOTENCY_KEY)) {
                http.setHeader(IDEMPOTENCY_KEY, newIdempotencyKey());
            }
            return super.executeInteraction(client, request);
        }
    }

    @State("account is OPEN")
    Map<String, Object> accountIsOpen() {
        return Map.of("accountId", openAccount().accountId().toString());
    }

    @State("account is OPEN with booked balance")
    Map<String, Object> accountIsOpenWithBookedBalance(Map<String, Object> parameters) {
        Account account = openAccount();
        funding.fund(account.accountId(), ((Number) parameters.get("bookedMinor")).longValue(), "EUR", STATE_ACTOR);
        return Map.of("accountId", account.accountId().toString());
    }

    @State("account is BLOCKED")
    Map<String, Object> accountIsBlocked(Map<String, Object> parameters) {
        Account account = openAccount();
        var reason = BlockReason.valueOf((String) parameters.get("reason"));
        accounts.block(account.accountId(), reason, null, stateCaller());
        return Map.of("accountId", account.accountId().toString());
    }

    @State("caller is a compliance officer")
    void callerIsAComplianceOfficer() {
        caller = bearer(StaffTokens.token("pact-compliance", "COMPLIANCE_OFFICER"));
    }

    @State("caller is risk-service")
    void callerIsRiskService() {
        caller = bearer(StaffTokens.serviceToken("risk-service", "ledger:sanctions-block"));
    }

    @PactVerifyProvider("AccountOpened: a person's account")
    MessageAndMetadata personAccountOpened() {
        return published(openAccount(), "AccountOpened");
    }

    @PactVerifyProvider("AccountOpened: an organisation's account")
    MessageAndMetadata organisationAccountOpened() {
        return published(openAccount(Holder.of(HolderType.ORGANISATION, "ACME GmbH", null, "DE",
                "529900T8BM49AURSDO55")), "AccountOpened");
    }

    @PactVerifyProvider("AccountBlocked: an operator block")
    MessageAndMetadata accountBlocked() {
        Account account = openAccount();
        accounts.block(account.accountId(), BlockReason.OPERATOR, null, stateCaller());
        return published(account, "AccountBlocked");
    }

    @PactVerifyProvider("AccountUnblocked: the last block lifted")
    MessageAndMetadata accountUnblocked() {
        Account account = openAccount();
        accounts.block(account.accountId(), BlockReason.OPERATOR, null, stateCaller());
        accounts.unblock(account.accountId(), BlockReason.OPERATOR, null, stateCaller());
        return published(account, "AccountUnblocked");
    }

    @PactVerifyProvider("AccountClosed: an empty account closed")
    MessageAndMetadata accountClosed() {
        Account account = openAccount();
        accounts.close(account.accountId(), STATE_ACTOR);
        return published(account, "AccountClosed");
    }

    /** The newest outbox row of {@code type} for the account: what the relay publishes, bytes and headers. */
    private MessageAndMetadata published(Account account, String type) {
        return jdbc.sql("""
                        SELECT payload::text, headers::text FROM outbox
                        WHERE topic = 'accounts.events' AND message_key = ? AND message_type = ?
                        ORDER BY id DESC LIMIT 1""")
                .param(account.accountId().toString())
                .param(type)
                .query((rs, row) -> new MessageAndMetadata(rs.getString(1).getBytes(StandardCharsets.UTF_8),
                        JSON.readValue(rs.getString(2), new TypeReference<Map<String, Object>>() {})))
                .single();
    }

    private static Caller stateCaller() {
        return new Caller(STATE_ACTOR, true, true);
    }

    private Account openAccount() {
        return openAccount(Holder.of(HolderType.PERSON, "Anna Schmidt", null, "DE", null));
    }

    private Account openAccount(Holder holder) {
        return accounts.open(new OpenAccount(new CustomerId("SYS-" + UUID.randomUUID()), holder), STATE_ACTOR);
    }
}
