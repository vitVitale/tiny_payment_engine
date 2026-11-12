package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.invariantchecker.LedgerInvariants;
import com.tpe.invariantchecker.Scope;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;

/**
 * A system test against ledger-service on the compose stack ({@code docker compose -f deploy/docker-compose.yml up -d
 * --wait postgres kafka oauth2}, run with {@code -Dtpe.local-stack=true}): the service process, the
 * {@code accounts.events} test projection, and after every test the per-test global invariant checks over the
 * accounts the test opened (specs/20-testing-strategy.md §2, §3). Later milestones add their invariants here.
 *
 * <p>Every subclass carries {@code @EnabledIfSystemProperty(named = "tpe.local-stack", matches = "true")} itself: the
 * condition is not {@code @Inherited}, so on this class it would not stop the plain build from starting the harness.
 */
public abstract class LedgerSystemTest {

    /** The ledger database as deploy/postgres/init.sql creates it (local credentials only). */
    private static final String LEDGER_DB = "jdbc:postgresql://localhost:5432/ledger";
    private static final String LEDGER_USER = "ledger";
    private static final String LEDGER_PASSWORD = "ledger-local";

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
        try (var connection = DriverManager.getConnection(LEDGER_DB, LEDGER_USER, LEDGER_PASSWORD)) {
            assertThat(LedgerInvariants.check(connection, Scope.accounts(accounts)))
                    .as("INV-1, INV-2, INV-12 over %s", accounts)
                    .isEmpty();
        }
    }

    /** The ledger account API, P-10 ({@link LedgerAccounts}); open through {@link #open} so the account is checked. */
    protected static LedgerAccounts ledger() {
        return ACCOUNTS;
    }

    /** {@code POST /v1/accounts} for {@code customer} as {@code authorization}; the account joins the checks. */
    protected Response open(String authorization, TestData.Customer customer) {
        Response opened = ACCOUNTS.open(authorization, customer);
        if (opened.status() == 201) {
            accounts.add(UUID.fromString(opened.body().get("accountId").asString()));
        }
        return opened;
    }

    /**
     * {@code POST path} to the ledger beyond its account API (holds: P-04's ground, not P-10's), with a fresh
     * {@code Idempotency-Key}; {@code authorization} left out when {@code null}.
     */
    protected Response post(String authorization, String path, Map<String, ?> body) {
        return HTTP.post(authorization, path, body);
    }
}
