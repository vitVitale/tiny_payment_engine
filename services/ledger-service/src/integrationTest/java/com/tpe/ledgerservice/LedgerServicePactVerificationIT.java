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
import com.tpe.ledgerservice.application.reservation.CreateHold;
import com.tpe.ledgerservice.application.reservation.HoldService;
import com.tpe.ledgerservice.application.reservation.SettlementService;
import com.tpe.ledgerservice.domain.account.Account;
import com.tpe.ledgerservice.domain.account.BlockReason;
import com.tpe.ledgerservice.domain.account.CustomerId;
import com.tpe.ledgerservice.domain.account.Holder;
import com.tpe.ledgerservice.domain.account.HolderType;
import com.tpe.paymentdomain.Iban;
import com.tpe.testsupport.pact.PactVerification;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.hc.core5.http.HttpRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestTemplate;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * ledger-service verifies every collected pact that names it (specs/20-testing-strategy.md §10.3): P-04, the
 * orchestrator's holds, P-10, the account calls of the system-test harness, and M-04, the {@code accounts.events}
 * snapshots its projections read. Runs only
 * with {@code -Ppact-verify}, against this commit's pacts and the last release's ({@link PactVerification}).
 *
 * <p>States seed accounts and holds through the application services and hand the new account's id or IBAN and a
 * fresh hold id to the request. The
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

    /** Account numbers no account of the ledger has: its own sequence counts up from 1, far below these. */
    private static final AtomicLong UNUSED_ACCOUNT_NUMBERS = new AtomicLong(9_900_000_000L);

    @Autowired
    AccountService accounts;

    @Autowired
    FundingService funding;

    @Autowired
    HoldService holds;

    @Autowired
    SettlementService settlements;

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
    void PACT_P04_PACT_P10_PACT_M04_verify(PactVerificationContext context) {
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

    @State("account is OPEN with available balance")
    Map<String, Object> accountIsOpenWithAvailableBalance(Map<String, Object> parameters) {
        Account payer = fundedAccount(amount(parameters, "availableMinor"));
        return Map.of("debtorIban", payer.iban().value(), "holdId", UUID.randomUUID().toString());
    }

    @State("hold is ACTIVE")
    Map<String, Object> holdIsActive(Map<String, Object> parameters) {
        return activeHold(amount(parameters, "amountMinor"));
    }

    @State("hold is ACTIVE and the payee account is OPEN")
    Map<String, Object> holdIsActiveAndThePayeeAccountIsOpen(Map<String, Object> parameters) {
        return withPayee(activeHold(amount(parameters, "amountMinor")), openAccount().iban().value());
    }

    @State("hold is SETTLED on-us")
    Map<String, Object> holdIsSettledOnUs(Map<String, Object> parameters) {
        Map<String, Object> hold = holdIsActiveAndThePayeeAccountIsOpen(parameters);
        settlements.settleOnUs(UUID.fromString((String) hold.get("holdId")),
                new Iban((String) hold.get("creditorIban")));
        return hold;
    }

    @State("no account has the payer IBAN")
    Map<String, Object> noAccountHasThePayerIban() {
        return Map.of("debtorIban", unusedIban(), "holdId", UUID.randomUUID().toString());
    }

    @State("payer account is BLOCKED")
    Map<String, Object> payerAccountIsBlocked() {
        Account payer = openAccount();
        accounts.block(payer.accountId(), BlockReason.OPERATOR, null, stateCaller());
        return Map.of("debtorIban", payer.iban().value(), "holdId", UUID.randomUUID().toString());
    }

    @State("payer account is CLOSED")
    Map<String, Object> payerAccountIsClosed() {
        Account payer = openAccount();
        accounts.close(payer.accountId(), STATE_ACTOR);
        return Map.of("debtorIban", payer.iban().value(), "holdId", UUID.randomUUID().toString());
    }

    /** A row the customer-number constraint never saw, as after a migration that added it NOT VALID (12 §5). */
    @State("payer account has no customer number")
    Map<String, Object> payerAccountHasNoCustomerNumber(Map<String, Object> parameters) {
        Account payer = fundedAccount(amount(parameters, "availableMinor"));
        String constraint = jdbc.sql("""
                SELECT conname FROM pg_constraint
                 WHERE conrelid = 'accounts'::regclass AND pg_get_constraintdef(oid) LIKE '%customer_id IS NOT NULL%'""")
                .query(String.class).single();
        jdbc.sql("ALTER TABLE accounts DROP CONSTRAINT " + constraint).update();
        try {
            jdbc.sql("UPDATE accounts SET customer_id = NULL WHERE account_id = ?").param(payer.accountId()).update();
        } finally {
            jdbc.sql("ALTER TABLE accounts ADD CONSTRAINT " + constraint
                    + " CHECK (kind = 'TECHNICAL' OR customer_id IS NOT NULL) NOT VALID").update();
        }
        return Map.of("debtorIban", payer.iban().value(), "holdId", UUID.randomUUID().toString());
    }

    /** The release came first and left a tombstone (ADR-009). */
    @State("hold was released")
    Map<String, Object> holdWasReleased(Map<String, Object> parameters) {
        Account payer = fundedAccount(amount(parameters, "availableMinor"));
        UUID holdId = UUID.randomUUID();
        holds.release(holdId);
        return Map.of("debtorIban", payer.iban().value(), "holdId", holdId.toString());
    }

    @State("no hold has the id")
    Map<String, Object> noHoldHasTheId() {
        return Map.of("holdId", UUID.randomUUID().toString());
    }

    @State("hold is ACTIVE and no account has the payee IBAN")
    Map<String, Object> holdIsActiveAndNoAccountHasThePayeeIban(Map<String, Object> parameters) {
        return withPayee(activeHold(amount(parameters, "amountMinor")), unusedIban());
    }

    @State("hold is ACTIVE and the payee account is BLOCKED")
    Map<String, Object> holdIsActiveAndThePayeeAccountIsBlocked(Map<String, Object> parameters) {
        Account payee = openAccount();
        accounts.block(payee.accountId(), BlockReason.OPERATOR, null, stateCaller());
        return withPayee(activeHold(amount(parameters, "amountMinor")), payee.iban().value());
    }

    @State("hold is ACTIVE and the payee account is CLOSED")
    Map<String, Object> holdIsActiveAndThePayeeAccountIsClosed(Map<String, Object> parameters) {
        Account payee = openAccount();
        accounts.close(payee.accountId(), STATE_ACTOR);
        return withPayee(activeHold(amount(parameters, "amountMinor")), payee.iban().value());
    }

    @State("hold is SETTLED on-us to another payee")
    Map<String, Object> holdIsSettledOnUsToAnotherPayee(Map<String, Object> parameters) {
        Map<String, Object> hold = holdIsSettledOnUs(parameters);
        return withPayee(hold, openAccount().iban().value());
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

    /** An {@code ACTIVE} hold of {@code amountMinor} on a funded payer: its id and the payer's IBAN. */
    private Map<String, Object> activeHold(long amountMinor) {
        Account payer = fundedAccount(amountMinor);
        UUID holdId = UUID.randomUUID();
        holds.create(new CreateHold(holdId, payer.iban(), amountMinor, "EUR", holdId, "TPEPACTSTATE"));
        return Map.of("debtorIban", payer.iban().value(), "holdId", holdId.toString());
    }

    private static Map<String, Object> withPayee(Map<String, Object> hold, String creditorIban) {
        Map<String, Object> state = new HashMap<>(hold);
        state.put("creditorIban", creditorIban);
        return state;
    }

    /** A valid IBAN of the own bank code that no account has: {@code DE} + mod 97-10 check digits + BBAN. */
    private static String unusedIban() {
        String bban = "10010010" + String.format("%010d", UNUSED_ACCOUNT_NUMBERS.incrementAndGet());
        // "DE00" moved behind the BBAN, letters as 10..35: D = 13, E = 14
        String digits = bban + "131400";
        int remainder = 0;
        for (int i = 0; i < digits.length(); i++) {
            remainder = (remainder * 10 + (digits.charAt(i) - '0')) % 97;
        }
        return "DE" + String.format("%02d", 98 - remainder) + bban;
    }

    private Account fundedAccount(long amountMinor) {
        Account account = openAccount();
        funding.fund(account.accountId(), amountMinor, "EUR", STATE_ACTOR);
        return account;
    }

    private static long amount(Map<String, Object> parameters, String name) {
        return ((Number) parameters.get(name)).longValue();
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
