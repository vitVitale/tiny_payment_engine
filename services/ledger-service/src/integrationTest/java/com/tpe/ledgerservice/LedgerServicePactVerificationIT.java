package com.tpe.ledgerservice;

import au.com.dius.pact.provider.junit5.HttpTestTarget;
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
import java.util.Map;
import java.util.UUID;
import org.apache.hc.core5.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ledger-service verifies every collected pact that names it (specs/20-testing-strategy.md §10.3): for now P-10, the
 * account calls of the system-test harness. Runs only with {@code -Ppact-verify}, against this commit's pacts and the
 * last release's ({@link PactVerification}).
 *
 * <p>States seed accounts through the application services and hand the new account's id to the request path. The
 * pact's {@code Authorization} is the consumer's example: each request carries a token of this JVM's key instead, of a
 * back-office operator unless a {@code caller is …} state names another caller. Each request also gets a fresh
 * {@code Idempotency-Key}, so the example key of one interaction never replays another's response.
 */
@Provider("ledger-service")
@PactVerification
class LedgerServicePactVerificationIT extends LedgerStack {

    private static final String STATE_ACTOR = "pact-state";

    @Autowired
    AccountService accounts;

    @Autowired
    FundingService funding;

    private String caller;

    @BeforeEach
    void target(PactVerificationContext context) {
        caller = operator("pact-operator");
        context.setTarget(new HttpTestTarget("localhost", port));
    }

    @TestTemplate
    @ExtendWith(PactVerificationSpringProvider.class)
    void PACT_P10_verify(PactVerificationContext context, HttpRequest request) {
        if (request.containsHeader(AUTHORIZATION)) {
            request.setHeader(AUTHORIZATION, caller);
        }
        if (request.containsHeader(IDEMPOTENCY_KEY)) {
            request.setHeader(IDEMPOTENCY_KEY, newIdempotencyKey());
        }
        context.verifyInteraction();
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
        accounts.block(account.accountId(), reason, null, new Caller(STATE_ACTOR, true, true));
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

    private Account openAccount() {
        var holder = Holder.of(HolderType.PERSON, "Anna Schmidt", null, "DE", null);
        return accounts.open(new OpenAccount(new CustomerId("SYS-" + UUID.randomUUID()), holder), STATE_ACTOR);
    }
}
