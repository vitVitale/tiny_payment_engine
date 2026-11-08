package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.invariantchecker.LedgerInvariants;
import com.tpe.invariantchecker.Scope;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A system test against ledger-service on the compose stack ({@code docker compose -f deploy/docker-compose.yml up -d
 * --wait postgres kafka oauth2}, run with {@code -Dtpe.local-stack=true}): the service process, the
 * {@code accounts.events} test projection, and after every test the per-test global invariant checks over the
 * accounts the test opened (specs/20-testing-strategy.md §2, §3). Later milestones add their invariants here.
 */
@EnabledIfSystemProperty(named = "tpe.local-stack", matches = "true",
        disabledReason = "needs the compose stack; run with -Dtpe.local-stack=true")
public abstract class LedgerSystemTest {

    /** The ledger database as deploy/postgres/init.sql creates it (local credentials only). */
    private static final String LEDGER_DB = "jdbc:postgresql://localhost:5432/ledger";
    private static final String LEDGER_USER = "ledger";
    private static final String LEDGER_PASSWORD = "ledger-local";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    protected static final AccountsProjection PROJECTION = new AccountsProjection();
    private static final AccountsEventsConsumer CONSUMER;
    private static final LedgerService LEDGER;

    static {
        CONSUMER = AccountsEventsConsumer.start("localhost:9092", PROJECTION);
        LEDGER = LedgerService.get();
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

    /** A response of the ledger: status and JSON body ({@code null} when empty). */
    public record Response(int status, JsonNode body) {

        /** The {@code code} of a Problem Details body. */
        public String code() {
            return body == null || body.get("code") == null ? null : body.get("code").asString();
        }
    }

    /** {@code POST /v1/accounts} for {@code customer} as {@code authorization}; the account joins the checks. */
    protected Response open(String authorization, TestData.Customer customer) {
        Response opened = post(authorization, "/v1/accounts", Map.of("customerId", customer.customerId(),
                "holder", Map.of("type", "PERSON", "name", customer.name(), "countryOfResidence", "DE")));
        if (opened.status() == 201) {
            accounts.add(UUID.fromString(opened.body().get("accountId").asString()));
        }
        return opened;
    }

    /** {@code POST path} with a fresh {@code Idempotency-Key}; {@code authorization} left out when {@code null}. */
    protected Response post(String authorization, String path, Map<String, ?> body) {
        var request = HttpRequest.newBuilder(LEDGER.baseUrl().resolve(path))
                .header("Content-Type", "application/json")
                .header("Idempotency-Key", "systest-" + UUID.randomUUID())
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        return send(request.build());
    }

    /** {@code GET path} as {@code authorization}. */
    protected Response get(String authorization, String path) {
        return send(HttpRequest.newBuilder(LEDGER.baseUrl().resolve(path))
                .header("Authorization", authorization).GET().build());
    }

    private static Response send(HttpRequest request) {
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();
            return new Response(response.statusCode(), body == null || body.isBlank() ? null : JSON.readTree(body));
        } catch (IOException e) {
            throw new IllegalStateException(request.method() + " " + request.uri() + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
