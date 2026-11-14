package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.invariantchecker.LedgerInvariants;
import com.tpe.invariantchecker.Scope;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * A system test against ledger-service on the compose stack ({@code docker compose -f deploy/docker-compose.yml up -d
 * --wait postgres kafka oauth2}, run with {@code -Dtpe.local-stack=true}): the service process, the
 * {@code accounts.events} test projection, and after every test the per-test global invariant checks over the
 * accounts the test opened (specs/20-testing-strategy.md §2, §3); after the class, INV-14 over every service log. A
 * failed test leaves a {@link DebugBundle}. {@link PaymentSystemTest} adds the payment services and their invariants.
 *
 * <p>Every subclass carries {@code @EnabledIfSystemProperty(named = "tpe.local-stack", matches = "true")} itself: the
 * condition is not {@code @Inherited}, so on this class it would not stop the plain build from starting the harness.
 */
@ExtendWith(DebugBundle.class)
public abstract class LedgerSystemTest implements DebugBundle.Subject {

    protected static final AccountsProjection PROJECTION = new AccountsProjection();
    private static final AccountsEventsConsumer CONSUMER;
    private static final LedgerAccounts ACCOUNTS;
    private static final JsonHttp HTTP;

    static {
        CONSUMER = AccountsEventsConsumer.start("localhost:9092", PROJECTION);
        var ledger = ServiceProcess.of(ServiceJar.LEDGER);
        ACCOUNTS = new LedgerAccounts(ledger.baseUrl());
        HTTP = new JsonHttp(ledger.baseUrl());
    }

    /** The accounts this test opened: the scope of its invariant checks. */
    private final List<UUID> accounts = new CopyOnWriteArrayList<>();

    @AfterEach
    void ledgerInvariantsHoldForTheTestsAccounts() throws SQLException {
        CONSUMER.assertHealthy();
        try (var connection = LocalDatabases.ledger()) {
            assertThat(LedgerInvariants.check(connection, Scope.accounts(accounts)))
                    .as("INV-1, INV-2, INV-12 over %s", accounts)
                    .isEmpty();
        }
    }

    /**
     * INV-14 once per test class, which is once per suite for each class (20 §3): the logs of every service started so
     * far, for every name and IBAN the tests of this JVM used.
     */
    @AfterAll
    static void noPersonalDataInTheServiceLogs() {
        assertThat(PersonalDataLogScan.violations(Path.of("target"), TestData.personalData()))
                .as("INV-14 over target/*-systemtest.log")
                .isEmpty();
    }

    /** The ledger account API, P-10 ({@link LedgerAccounts}); open through {@link #open} so the account is checked. */
    protected static LedgerAccounts ledger() {
        return ACCOUNTS;
    }

    /**
     * {@code POST /v1/accounts} for {@code customer} as {@code authorization}; the account joins the checks and its IBAN
     * the personal data INV-14 looks for.
     */
    protected Response open(String authorization, TestData.Customer customer) {
        Response opened = ACCOUNTS.open(authorization, customer);
        if (opened.status() == 201) {
            accounts.add(UUID.fromString(opened.body().get("accountId").asString()));
            TestData.rememberIban(opened.body().get("iban").asString());
        }
        return opened;
    }

    /** The accounts this test opened, in order. */
    protected List<UUID> accounts() {
        return List.copyOf(accounts);
    }

    /**
     * {@code POST path} to the ledger beyond its account API (holds: P-04's ground, not P-10's), with a fresh
     * {@code Idempotency-Key}; {@code authorization} left out when {@code null}.
     */
    protected Response post(String authorization, String path, Map<String, ?> body) {
        return HTTP.post(authorization, path, body);
    }

    /** The test's accounts and their postings, from the ledger database. */
    @Override
    public String debugBundle() throws SQLException {
        try (var ledger = LocalDatabases.ledger()) {
            return SqlDump.of(ledger, "ledger accounts", """
                    SELECT account_id, iban, status, booked_minor, held_minor FROM accounts
                     WHERE account_id = ANY (?) ORDER BY opened_at""", accounts)
                    + SqlDump.of(ledger, "ledger postings of the test's accounts", """
                    SELECT e.created_at, e.entry_type, e.payment_id, p.account_id, p.side, p.amount_minor
                      FROM postings p JOIN journal_entries e ON e.entry_id = p.entry_id
                     WHERE p.account_id = ANY (?) ORDER BY e.created_at, p.posting_id""", accounts)
                    + SqlDump.of(ledger, "ledger holds of the test's accounts", """
                    SELECT hold_id, account_id, amount_minor, status, payment_id, entry_id, finalised_at FROM holds
                     WHERE account_id = ANY (?) ORDER BY created_at""", accounts);
        }
    }
}
