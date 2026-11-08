package com.tpe.ledgerservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.TechnicalAccounts;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Closing an account (specs/12-ledger-service.md §3.2, UC-10) and, 13 months later, its tombstone on
 * {@code accounts.events} (§4, ADR-007).
 */
class CloseIT extends LedgerStack {

    /** The advisory lock of the latched race. */
    private static final long LATCH = 5_001;
    private static final AtomicInteger CUSTOMERS = new AtomicInteger();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Test
    void LED_AC_14_closingAnAccountWithABalanceAnActiveHoldOrAnActivePendingCreditFailsAccountNotEmpty() {
        Customer withBalance = customer(1_000);
        // a hold never exceeds the booked balance, so it comes with one; AccountCloseTest covers the hold alone
        Customer withHold = customer(1_000);
        assertThat(createHold(UUID.randomUUID(), withHold.iban(), 1_000).getStatusCode().value()).isEqualTo(201);
        Customer withPendingCredit = customer(0);
        createPendingCredit(UUID.randomUUID(), withPendingCredit.iban(), 500);

        for (Customer customer : List.of(withBalance, withHold, withPendingCredit)) {
            assertProblem(close(operator("op-1"), customer.accountId()), 422, "ACCOUNT_NOT_EMPTY");
            assertThat(status(customer.accountId())).isEqualTo("OPEN");
            assertThat(audit(customer.accountId())).isEmpty();
            assertThat(eventTypes(customer.accountId())).doesNotContain("AccountClosed");
        }
    }

    @Test
    void LED_AC_18_closingWritesOneAuditRowWithTheActorAndPublishesAccountClosed() {
        Customer customer = customer(0);
        long revision = revision(customer.accountId());

        var closed = close(operator("operator-carla"), customer.accountId());

        assertThat(closed.getStatusCode().value()).isEqualTo(200);
        assertThat(closed.getBody().get("status").asString()).isEqualTo("CLOSED");
        String closedAt = closed.getBody().get("closedAt").asString();
        assertThat(audit(customer.accountId())).containsExactly("operator-carla");
        assertThat(revision(customer.accountId())).isEqualTo(revision + 1);
        JsonNode event = lastEvent(customer.accountId());
        assertThat(event.get("revision").asLong()).isEqualTo(revision + 1);
        assertThat(event.get("occurredAt").asString()).isEqualTo(closedAt);
        assertThat(event.get("account").get("status").asString()).isEqualTo("CLOSED");
        assertThat(event.get("account").get("closedAt").asString()).isEqualTo(closedAt);
        assertThat(eventTypes(customer.accountId())).containsExactly("AccountOpened", "AccountClosed");
    }

    @Test
    void anAccountWhoseOnlyMemosAreReleasedSettledCancelledOrTombstonesCanBeClosed() {
        Customer customer = customer(1_000);
        UUID released = UUID.randomUUID();
        createHold(released, customer.iban(), 400);
        post("/v1/holds/" + released + "/release", Map.of());
        createHold(UUID.randomUUID(), customer.iban(), 1_000);
        settleAll(customer.accountId());
        UUID cancelled = UUID.randomUUID();
        createPendingCredit(cancelled, customer.iban(), 700);
        post("/v1/pending-credits/" + cancelled + "/cancel", Map.of());
        post("/v1/holds/" + UUID.randomUUID() + "/release", Map.of());
        post("/v1/pending-credits/" + UUID.randomUUID() + "/cancel", Map.of());

        var closed = close(operator("op-1"), customer.accountId());

        assertThat(closed.getStatusCode().value()).isEqualTo(200);
        assertThat(status(customer.accountId())).isEqualTo("CLOSED");
    }

    @Test
    void aSanctionsBlockedAccountCannotBeClosedUntilComplianceLiftsTheBlockWhileAnOperatorBlockedOneCan() {
        Customer sanctioned = customer(0);
        String officer = bearer(StaffTokens.token("officer-1", "COMPLIANCE_OFFICER"));
        assertThat(lifecycle(officer, sanctioned.accountId(), "block", Map.of("reason", "SANCTIONS"))
                .getStatusCode().value()).isEqualTo(200);
        Customer operatorBlocked = customer(0);
        lifecycle(operator("op-1"), operatorBlocked.accountId(), "block", Map.of("reason", "OPERATOR"));
        long revision = revision(sanctioned.accountId());

        assertProblem(close(operator("op-1"), sanctioned.accountId()), 422, "ACCOUNT_BLOCKED");
        assertThat(status(sanctioned.accountId())).isEqualTo("BLOCKED");
        assertThat(revision(sanctioned.accountId())).isEqualTo(revision);
        assertThat(audit(sanctioned.accountId())).isEmpty();
        assertThat(eventTypes(sanctioned.accountId())).doesNotContain("AccountClosed");

        lifecycle(officer, sanctioned.accountId(), "unblock", Map.of("reason", "SANCTIONS"));

        assertThat(close(operator("op-1"), sanctioned.accountId()).getStatusCode().value()).isEqualTo(200);
        var closedWithOperatorBlock = close(operator("op-1"), operatorBlocked.accountId());
        assertThat(closedWithOperatorBlock.getStatusCode().value()).isEqualTo(200);
        assertThat(closedWithOperatorBlock.getBody().get("status").asString()).isEqualTo("CLOSED");
    }

    @Test
    void aClosedAccountCannotBeBlockedUnblockedHeldCreditedOrClosedAgain() {
        Customer customer = customer(0);
        close(operator("op-1"), customer.accountId());

        assertProblem(lifecycle(operator("op-1"), customer.accountId(), "block", Map.of("reason", "OPERATOR")), 422,
                "ACCOUNT_CLOSED");
        assertProblem(lifecycle(operator("op-1"), customer.accountId(), "unblock", Map.of("reason", "OPERATOR")),
                422, "ACCOUNT_CLOSED");
        assertProblem(createHold(UUID.randomUUID(), customer.iban(), 1), 422, "ACCOUNT_CLOSED");
        assertProblem(createPendingCredit(UUID.randomUUID(), customer.iban(), 1), 422, "ACCOUNT_CLOSED");
        assertProblem(close(operator("op-1"), customer.accountId()), 422, "ACCOUNT_CLOSED");
        assertThat(audit(customer.accountId())).hasSize(1);
        assertThat(eventTypes(customer.accountId())).containsExactly("AccountOpened", "AccountClosed");
    }

    @Test
    void closeIsForBackofficeOperatorsOnCustomerAccountsAndNeedsAnIdempotencyKey() {
        Customer customer = customer(0);

        assertProblem(close(bearer(StaffTokens.serviceToken("risk-service", "ledger:sanctions-block")),
                customer.accountId()), 403, "FORBIDDEN");
        assertProblem(close(null, customer.accountId()), 401, "UNAUTHENTICATED");
        assertProblem(lifecycle(operator("op-1"), customer.accountId(), "close", null, null), 400,
                "IDEMPOTENCY_KEY_MISSING");
        assertProblem(close(operator("op-1"), UUID.randomUUID()), 422, "ACCOUNT_NOT_FOUND");
        assertProblem(close(operator("op-1"), TechnicalAccounts.CSM_SETTLEMENT_ID), 422, "ACCOUNT_NOT_CUSTOMER");
        assertThat(status(customer.accountId())).isEqualTo("OPEN");
    }

    @Test
    void aCloseRetriedWithItsKeyReplaysTheStoredResponse() {
        Customer customer = customer(0);
        String key = newIdempotencyKey();

        var first = lifecycle(operator("op-1"), customer.accountId(), "close", key, Map.of());
        var replayed = lifecycle(operator("op-1"), customer.accountId(), "close", key, Map.of());

        assertThat(first.getStatusCode().value()).isEqualTo(200);
        assertThat(replayed.getStatusCode().value()).isEqualTo(200);
        assertThat(replayed.getBody()).isEqualTo(first.getBody());
        assertThat(audit(customer.accountId())).hasSize(1);
    }

    /**
     * 12 §6.2: a pending credit being created holds the account row {@code FOR SHARE} and waits in a trigger for a
     * latch the test holds. The close waits for the row, then reads the committed credit and refuses.
     */
    @Test
    void aCloseWhileAPendingCreditIsCreatedWaitsForItAndRefusesAccountNotEmpty() throws Exception {
        Customer customer = customer(0);
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION latched_close() RETURNS trigger AS $$
                BEGIN
                  IF NEW.pending_credit_id = '%s' THEN
                    PERFORM pg_advisory_xact_lock_shared(%d);
                  END IF;
                  RETURN NEW;
                END $$ LANGUAGE plpgsql""".formatted(id, LATCH)).update();
        jdbc.sql("CREATE TRIGGER latched_close BEFORE INSERT ON pending_credits FOR EACH ROW "
                + "EXECUTE FUNCTION latched_close()").update();
        ResponseEntity<JsonNode> created;
        ResponseEntity<JsonNode> closed;
        // the latch closes first, so a failed wait never leaves the threads stuck on it
        try (var threads = Executors.newFixedThreadPool(2); Connection latch = dataSource.getConnection()) {
            latch.createStatement().execute("SELECT pg_advisory_lock(" + LATCH + ")");
            Future<ResponseEntity<JsonNode>> create = threads.submit(
                    () -> createPendingCredit(id, customer.iban(), 500));
            awaitWaiting("advisory");
            Future<ResponseEntity<JsonNode>> close = threads.submit(() -> close(operator("op-1"),
                    customer.accountId()));
            awaitWaiting("transactionid");
            latch.createStatement().execute("SELECT pg_advisory_unlock(" + LATCH + ")");
            created = create.get();
            closed = close.get();
        } finally {
            jdbc.sql("DROP TRIGGER latched_close ON pending_credits").update();
            jdbc.sql("DROP FUNCTION latched_close()").update();
        }

        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertProblem(closed, 422, "ACCOUNT_NOT_EMPTY");
        assertThat(status(customer.accountId())).isEqualTo("OPEN");
    }

    /**
     * ADR-007: one null-value record for the key, 13 months after the close and not a minute before. Closed on a
     * month end, so the due day is the clamped 28 February.
     */
    @Test
    void thirteenMonthsAfterTheCloseTheJobPublishesOneTombstoneForTheAccountKey() {
        Instant start = CLOCK.instant();
        try {
            Customer customer = customer(0);
            CLOCK.set(Instant.parse("2026-01-31T12:00:00Z"));
            close(operator("op-1"), customer.accountId());
            Instant due = Instant.parse("2027-02-28T12:00:00Z");
            assertThat(due).isEqualTo(Instant.parse("2026-01-31T12:00:00Z").atZone(ZoneOffset.UTC).plusMonths(13)
                    .toInstant());

            CLOCK.set(due.minus(Duration.ofMinutes(1)));
            pause(Duration.ofSeconds(1));
            assertThat(tombstones(customer.accountId())).isZero();

            CLOCK.set(due.plus(Duration.ofMinutes(1)));
            await().atMost(Duration.ofSeconds(10)).until(() -> tombstones(customer.accountId()) == 1);
            pause(Duration.ofSeconds(1));

            assertThat(tombstones(customer.accountId())).isOne();
            assertThat(jdbc.sql("SELECT events_tombstoned_at IS NOT NULL FROM accounts WHERE account_id = ?")
                    .param(customer.accountId()).query(Boolean.class).single()).isTrue();
            await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql("""
                    SELECT count(*) FROM outbox
                     WHERE message_key = ? AND payload IS NULL AND published_at IS NOT NULL""")
                    .param(customer.accountId().toString()).query(Long.class).single() == 1);
        } finally {
            CLOCK.set(start);
        }
    }

    private long tombstones(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE topic = 'accounts.events' AND message_key = ? "
                + "AND payload IS NULL").param(accountId.toString()).query(Long.class).single();
    }

    private record Customer(UUID accountId, String iban) {}

    private Customer customer(long fundedMinor) {
        UUID accountId = openCustomer("CLOSE-" + UUID.randomUUID().toString().substring(0, 8) + "-"
                + CUSTOMERS.incrementAndGet());
        if (fundedMinor > 0) {
            assertThat(fund(operator("op-1"), newIdempotencyKey(), accountId, eur(fundedMinor)).getStatusCode()
                    .value()).isEqualTo(200);
        }
        String iban = jdbc.sql("SELECT iban FROM accounts WHERE account_id = ?").param(accountId)
                .query(String.class).single();
        return new Customer(accountId, iban);
    }

    private ResponseEntity<JsonNode> createHold(UUID holdId, String iban, long amountMinor) {
        return post("/v1/holds", Map.of("holdId", holdId.toString(), "debtorIban", iban, "amountMinor", amountMinor,
                "currency", "EUR"));
    }

    /** Settles the account's {@code ACTIVE} holds: their money leaves to the CSM. */
    private void settleAll(UUID accountId) {
        jdbc.sql("SELECT hold_id FROM holds WHERE account_id = ? AND status = 'ACTIVE'").param(accountId)
                .query(UUID.class).list()
                .forEach(holdId -> assertThat(post("/v1/holds/" + holdId + "/settle", Map.of()).getStatusCode()
                        .value()).isEqualTo(200));
    }

    private ResponseEntity<JsonNode> createPendingCredit(UUID id, String iban, long amountMinor) {
        return post("/v1/pending-credits", Map.of("pendingCreditId", id.toString(), "creditorIban", iban,
                "amountMinor", amountMinor, "currency", "EUR"));
    }

    private ResponseEntity<JsonNode> post(String uri, Map<String, ?> body) {
        return http().post().uri(uri).contentType(MediaType.APPLICATION_JSON).header(IDEMPOTENCY_KEY,
                newIdempotencyKey()).body(body).retrieve().toEntity(JsonNode.class);
    }

    private ResponseEntity<JsonNode> close(String authorization, UUID accountId) {
        return lifecycle(authorization, accountId, "close", newIdempotencyKey(), Map.of());
    }

    private ResponseEntity<JsonNode> lifecycle(String authorization, UUID accountId, String action,
            Map<String, ?> body) {
        return lifecycle(authorization, accountId, action, newIdempotencyKey(), body);
    }

    private ResponseEntity<JsonNode> lifecycle(String authorization, UUID accountId, String action,
            String idempotencyKey, Map<String, ?> body) {
        var request = http().post().uri("/v1/accounts/{id}/" + action, accountId)
                .contentType(MediaType.APPLICATION_JSON).body(body == null ? Map.of() : body);
        if (authorization != null) {
            request.header(AUTHORIZATION, authorization);
        }
        if (idempotencyKey != null) {
            request.header(IDEMPOTENCY_KEY, idempotencyKey);
        }
        return request.retrieve().toEntity(JsonNode.class);
    }

    private void awaitWaiting(String waitEvent) {
        await().atMost(Duration.ofSeconds(10)).until(() -> jdbc.sql("""
                SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND wait_event = ?""")
                .param(waitEvent).query(Long.class).single() >= 1);
    }

    private String status(UUID accountId) {
        return jdbc.sql("SELECT status FROM accounts WHERE account_id = ?").param(accountId).query(String.class)
                .single();
    }

    private long revision(UUID accountId) {
        return jdbc.sql("SELECT revision FROM accounts WHERE account_id = ?").param(accountId).query(Long.class)
                .single();
    }

    /** The actors of the account's {@code CLOSE} audit rows. */
    private List<String> audit(UUID accountId) {
        return jdbc.sql("SELECT actor FROM audit_log WHERE account_id = ? AND action = 'CLOSE' ORDER BY id")
                .param(accountId).query(String.class).list();
    }

    private List<String> eventTypes(UUID accountId) {
        return jdbc.sql("""
                SELECT message_type FROM outbox
                 WHERE topic = 'accounts.events' AND message_key = ? AND payload IS NOT NULL ORDER BY id""")
                .param(accountId.toString()).query(String.class).list();
    }

    private JsonNode lastEvent(UUID accountId) {
        return JSON.readTree(jdbc.sql("""
                SELECT payload::text FROM outbox
                 WHERE topic = 'accounts.events' AND message_key = ? AND payload IS NOT NULL ORDER BY id DESC LIMIT 1""")
                .param(accountId.toString()).query(String.class).single());
    }

    private static void pause(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().get("code").asString()).isEqualTo(code);
    }
}
