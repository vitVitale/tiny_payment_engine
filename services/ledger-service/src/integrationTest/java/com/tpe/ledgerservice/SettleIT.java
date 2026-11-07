package com.tpe.ledgerservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.contracts.TechnicalAccounts;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Settle and settle on-us of the Posting API (specs/12-ledger-service.md §3.1, §6.2, ADR-005): a hold becomes a
 * journal entry to the CSM settlement account or to the payee. No token until M5 (00 §12.1).
 */
class SettleIT extends LedgerStack {

    private static final AtomicInteger CUSTOMERS = new AtomicInteger();

    @Autowired
    JdbcClient jdbc;

    @Test
    void LED_AC_05_settlePostsDrPayerCrCsmSettlementLeavesNoActiveHoldAndRepeatsWithTheSameEntry() {
        Customer payer = fundedCustomer(10_000);
        UUID holdId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();
        createHold(holdId, payer, 3_000, paymentId);

        var settled = settle(holdId);
        var repeated = post("/v1/holds/" + holdId + "/settle", newIdempotencyKey(), Map.of());

        assertThat(settled.getStatusCode().value()).isEqualTo(200);
        JsonNode body = settled.getBody();
        assertThat(body.get("holdId").asString()).isEqualTo(holdId.toString());
        assertThat(body.get("status").asString()).isEqualTo("SETTLED");
        assertThat(body.has("settledAt")).isTrue();
        assertThat(body.has("creditorAccountId")).isFalse();
        UUID entryId = UUID.fromString(body.get("journalEntryId").asString());
        assertThat(repeated.getStatusCode().value()).isEqualTo(200);
        assertThat(repeated.getBody()).isEqualTo(body);

        assertThat(postings(entryId)).containsExactly(
                payer.accountId() + " DR 3000",
                TechnicalAccounts.CSM_SETTLEMENT_ID + " CR 3000");
        assertThat(jdbc.sql("SELECT entry_type || ' ' || payment_id FROM journal_entries WHERE entry_id = ?")
                .param(entryId).query(String.class).single()).isEqualTo("PAYMENT_OUT " + paymentId);
        assertThat(activeHolds(payer.accountId())).isZero();
        assertThat(jdbc.sql("SELECT entry_id FROM holds WHERE hold_id = ?").param(holdId).query(UUID.class).single())
                .isEqualTo(entryId);
        assertBalances(payer.accountId(), 7_000, 7_000);
        JsonNode read = http().get().uri("/v1/holds/{id}", holdId).retrieve().toEntity(JsonNode.class).getBody();
        assertThat(read.get("status").asString()).isEqualTo("SETTLED");
        assertThat(read.get("journalEntryId").asString()).isEqualTo(entryId.toString());
    }

    @Test
    void LED_AC_06_settleOnUsWithABlockedPayeeFailsAccountBlockedAndLeavesTheHoldActive() {
        Customer payer = fundedCustomer(10_000);
        Customer payee = fundedCustomer(0);
        block(payee.accountId());
        UUID holdId = UUID.randomUUID();
        createHold(holdId, payer, 2_000, UUID.randomUUID());

        var refused = settleOnUs(holdId, payee.iban());

        assertProblem(refused, 422, "ACCOUNT_BLOCKED");
        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
        assertBalances(payer.accountId(), 10_000, 8_000);
        assertBalances(payee.accountId(), 0, 0);
    }

    @Test
    void settleOnUsPostsDrPayerCrPayeeAndRepeatsWithTheSameResult() {
        Customer payer = fundedCustomer(10_000);
        Customer payee = fundedCustomer(500);
        UUID holdId = UUID.randomUUID();
        createHold(holdId, payer, 2_500, UUID.randomUUID());

        var settled = settleOnUs(holdId, payee.iban());
        var repeated = settleOnUs(holdId, payee.iban());

        assertThat(settled.getStatusCode().value()).isEqualTo(200);
        JsonNode body = settled.getBody();
        assertThat(body.get("status").asString()).isEqualTo("SETTLED");
        assertThat(body.get("creditorAccountId").asString()).isEqualTo(payee.accountId().toString());
        UUID entryId = UUID.fromString(body.get("journalEntryId").asString());
        assertThat(repeated.getStatusCode().value()).isEqualTo(200);
        assertThat(repeated.getBody()).isEqualTo(body);
        assertThat(postings(entryId)).containsExactly(payer.accountId() + " DR 2500", payee.accountId() + " CR 2500");
        assertThat(jdbc.sql("SELECT entry_type FROM journal_entries WHERE entry_id = ?").param(entryId)
                .query(String.class).single()).isEqualTo("ON_US");
        assertBalances(payer.accountId(), 7_500, 7_500);
        assertBalances(payee.accountId(), 3_000, 3_000);
    }

    @Test
    void settleOnUsRefusesAnUnknownClosedTechnicalOrUnnumberedPayeeAndLeavesTheHoldActive() {
        Customer payer = fundedCustomer(10_000);
        Customer closed = fundedCustomer(0);
        jdbc.sql("UPDATE accounts SET status = 'CLOSED', closed_at = now() WHERE account_id = ?")
                .param(closed.accountId()).update();
        String technical = jdbc.sql("SELECT iban FROM accounts WHERE account_id = ?")
                .param(TechnicalAccounts.CSM_SETTLEMENT_ID).query(String.class).single();
        UUID holdId = UUID.randomUUID();
        createHold(holdId, payer, 1_000, UUID.randomUUID());

        assertProblem(settleOnUs(holdId, "DE89370400440532013000"), 422, "ACCOUNT_NOT_FOUND");
        assertProblem(settleOnUs(holdId, closed.iban()), 422, "ACCOUNT_CLOSED");
        assertProblem(settleOnUs(holdId, technical), 422, "ACCOUNT_NOT_CUSTOMER");
        assertProblem(settleOnUs(holdId, "DE00XX"), 400, "VALIDATION_FAILED");
        assertThat(holdStatus(holdId)).isEqualTo("ACTIVE");
        assertBalances(payer.accountId(), 10_000, 9_000);
    }

    @Test
    void settleSucceedsOnAPayerBlockedAfterTheHoldWasCreated() {
        Customer payer = fundedCustomer(10_000);
        UUID holdId = UUID.randomUUID();
        createHold(holdId, payer, 4_000, UUID.randomUUID());
        block(payer.accountId());

        var settled = settle(holdId);

        assertThat(settled.getStatusCode().value()).isEqualTo(200);
        assertThat(settled.getBody().get("status").asString()).isEqualTo("SETTLED");
        assertBalances(payer.accountId(), 6_000, 6_000);
    }

    @Test
    void releaseAfterSettleIsHoldSettledAndSettleAfterReleaseIsHoldReleased() {
        Customer payer = fundedCustomer(10_000);
        Customer payee = fundedCustomer(0);
        UUID settledHold = UUID.randomUUID();
        UUID releasedHold = UUID.randomUUID();
        createHold(settledHold, payer, 1_000, UUID.randomUUID());
        createHold(releasedHold, payer, 2_000, UUID.randomUUID());
        settle(settledHold);
        release(releasedHold);

        assertProblem(release(settledHold), 409, "HOLD_SETTLED");
        assertProblem(settle(releasedHold), 409, "HOLD_RELEASED");
        assertProblem(settleOnUs(releasedHold, payee.iban()), 409, "HOLD_RELEASED");
        // a hold released before it existed (ADR-009)
        UUID tombstoned = UUID.randomUUID();
        release(tombstoned);
        assertProblem(settle(tombstoned), 409, "HOLD_RELEASED");
        assertBalances(payer.accountId(), 9_000, 9_000);
    }

    @Test
    void settlingAnUnknownHoldIsHoldNotFound() {
        Customer payee = fundedCustomer(0);

        assertProblem(settle(UUID.randomUUID()), 404, "HOLD_NOT_FOUND");
        assertProblem(settleOnUs(UUID.randomUUID(), payee.iban()), 404, "HOLD_NOT_FOUND");
    }

    @Test
    void aHoldSettledOneWayIsHoldSettledTheOtherWayAndOnUsToAnotherPayeeIsAConflict() {
        Customer payer = fundedCustomer(10_000);
        Customer payee = fundedCustomer(0);
        Customer other = fundedCustomer(0);
        UUID out = UUID.randomUUID();
        UUID onUs = UUID.randomUUID();
        createHold(out, payer, 1_000, UUID.randomUUID());
        createHold(onUs, payer, 1_000, UUID.randomUUID());
        settle(out);
        settleOnUs(onUs, payee.iban());

        assertProblem(settleOnUs(out, payee.iban()), 409, "HOLD_SETTLED");
        assertProblem(settle(onUs), 409, "HOLD_SETTLED");
        assertProblem(settleOnUs(onUs, other.iban()), 409, "IDEMPOTENCY_CONFLICT");
        assertBalances(payer.accountId(), 8_000, 8_000);
        assertBalances(payee.accountId(), 1_000, 1_000);
        assertBalances(other.accountId(), 0, 0);
    }

    @Test
    void theOrchestratorsKeysReplayTheStoredResponse() {
        Customer payer = fundedCustomer(10_000);
        Customer payee = fundedCustomer(0);
        UUID out = UUID.randomUUID();
        UUID onUs = UUID.randomUUID();
        createHold(out, payer, 1_000, UUID.randomUUID());
        createHold(onUs, payer, 1_000, UUID.randomUUID());

        var first = post("/v1/holds/" + out + "/settle", "hold-settle:" + out, Map.of());
        var replay = post("/v1/holds/" + out + "/settle", "hold-settle:" + out, Map.of());
        var firstOnUs = post("/v1/holds/" + onUs + "/settle-on-us", "hold-settle-on-us:" + onUs,
                Map.of("creditorIban", payee.iban()));
        var replayOnUs = post("/v1/holds/" + onUs + "/settle-on-us", "hold-settle-on-us:" + onUs,
                Map.of("creditorIban", payee.iban()));
        var noKey = post("/v1/holds/" + out + "/settle", null, Map.of());

        assertThat(replay.getBody()).isEqualTo(first.getBody());
        assertThat(replayOnUs.getBody()).isEqualTo(firstOnUs.getBody());
        assertProblem(noKey, 400, "IDEMPOTENCY_KEY_MISSING");
        assertBalances(payer.accountId(), 8_000, 8_000);
    }

    /** LED-AC-10: technical accounts take postings without a row update, so there is no hot row to conflict on. */
    @Test
    void LED_AC_10_twoHundredConcurrentSettlementsOfDifferentPayersDoNotConflictOnTheCsmSettlementAccount()
            throws Exception {
        int settlements = 200;
        List<UUID> holdIds = new ArrayList<>();
        for (int i = 0; i < settlements; i++) {
            UUID holdId = UUID.randomUUID();
            createHold(holdId, fundedCustomer(1_000), 100, UUID.randomUUID());
            holdIds.add(holdId);
        }
        long csmVersion = csmSettlementVersion();
        long csmPostings = csmSettlementPostings();
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> calls = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(settlements)) {
            for (UUID holdId : holdIds) {
                calls.add(pool.submit(() -> {
                    start.await();
                    return settle(holdId);
                }));
            }
            start.countDown();
            for (var call : calls) {
                ResponseEntity<JsonNode> response = call.get();
                assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
            }
        }

        assertThat(csmSettlementVersion()).isEqualTo(csmVersion);
        assertThat(csmSettlementPostings()).isEqualTo(csmPostings + settlements);
    }

    /** Both customer rows are locked in ascending {@code account_id} order (12 §6.2), whichever is the payer. */
    @Test
    void oppositeOnUsSettlementsBetweenTheSameTwoAccountsRunConcurrentlyWithoutDeadlock() throws Exception {
        Customer a = fundedCustomer(100_000);
        Customer b = fundedCustomer(100_000);
        int pairs = 25;
        List<UUID> aToB = new ArrayList<>();
        List<UUID> bToA = new ArrayList<>();
        for (int i = 0; i < pairs; i++) {
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            createHold(first, a, 100, UUID.randomUUID());
            createHold(second, b, 300, UUID.randomUUID());
            aToB.add(first);
            bToA.add(second);
        }
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> calls = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(2 * pairs)) {
            for (int i = 0; i < pairs; i++) {
                UUID first = aToB.get(i);
                UUID second = bToA.get(i);
                calls.add(pool.submit(() -> {
                    start.await();
                    return settleOnUs(first, b.iban());
                }));
                calls.add(pool.submit(() -> {
                    start.await();
                    return settleOnUs(second, a.iban());
                }));
            }
            start.countDown();
            for (var call : calls) {
                ResponseEntity<JsonNode> response = call.get();
                assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
            }
        }

        assertBalances(a.accountId(), 100_000 - pairs * 100 + pairs * 300, 100_000 - pairs * 100 + pairs * 300);
        assertBalances(b.accountId(), 100_000 - pairs * 300 + pairs * 100, 100_000 - pairs * 300 + pairs * 100);
    }

    private record Customer(UUID accountId, String iban) {}

    private Customer fundedCustomer(long amountMinor) {
        UUID accountId = openCustomer("SETTLE-" + UUID.randomUUID().toString().substring(0, 8) + "-"
                + CUSTOMERS.incrementAndGet());
        if (amountMinor > 0) {
            var funded = fund(operator("op-1"), newIdempotencyKey(), accountId, eur(amountMinor));
            assertThat(funded.getStatusCode().value()).isEqualTo(200);
        }
        String iban = jdbc.sql("SELECT iban FROM accounts WHERE account_id = ?").param(accountId)
                .query(String.class).single();
        return new Customer(accountId, iban);
    }

    private void block(UUID accountId) {
        var blocking = http().post().uri("/v1/accounts/{id}/block", accountId)
                .header(AUTHORIZATION, operator("op-1")).header(IDEMPOTENCY_KEY, newIdempotencyKey())
                .contentType(MediaType.APPLICATION_JSON).body(Map.of("reason", "OPERATOR"))
                .retrieve().toEntity(JsonNode.class);
        assertThat(blocking.getStatusCode().value()).isEqualTo(200);
    }

    private void createHold(UUID holdId, Customer payer, long amountMinor, UUID paymentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("holdId", holdId.toString());
        body.put("debtorIban", payer.iban());
        body.put("amountMinor", amountMinor);
        body.put("currency", "EUR");
        body.put("reference", Map.of("paymentId", paymentId.toString(), "txId", "TPE-TX-48"));
        var created = post("/v1/holds", "hold-create:" + holdId, body);
        assertThat(created.getStatusCode().value()).as("%s", created.getBody()).isEqualTo(201);
    }

    private ResponseEntity<JsonNode> settle(UUID holdId) {
        return post("/v1/holds/" + holdId + "/settle", "hold-settle:" + holdId + ":" + UUID.randomUUID(), Map.of());
    }

    private ResponseEntity<JsonNode> settleOnUs(UUID holdId, String creditorIban) {
        return post("/v1/holds/" + holdId + "/settle-on-us", "hold-settle-on-us:" + holdId + ":" + UUID.randomUUID(),
                Map.of("creditorIban", creditorIban));
    }

    private ResponseEntity<JsonNode> release(UUID holdId) {
        return post("/v1/holds/" + holdId + "/release", "hold-release:" + holdId + ":" + UUID.randomUUID(),
                Map.of());
    }

    private ResponseEntity<JsonNode> post(String uri, String key, Map<String, ?> body) {
        var request = http().post().uri(uri).contentType(MediaType.APPLICATION_JSON).body(body);
        if (key != null) {
            request.header(IDEMPOTENCY_KEY, key);
        }
        return request.retrieve().toEntity(JsonNode.class);
    }

    private List<String> postings(UUID entryId) {
        return jdbc.sql("""
                SELECT account_id || ' ' || side || ' ' || amount_minor FROM postings
                 WHERE entry_id = ? ORDER BY posting_id""")
                .param(entryId).query(String.class).list();
    }

    private String holdStatus(UUID holdId) {
        return jdbc.sql("SELECT status FROM holds WHERE hold_id = ?").param(holdId).query(String.class).single();
    }

    private long activeHolds(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM holds WHERE account_id = ? AND status = 'ACTIVE'").param(accountId)
                .query(Long.class).single();
    }

    private long csmSettlementVersion() {
        return jdbc.sql("SELECT version FROM accounts WHERE account_id = ?").param(TechnicalAccounts.CSM_SETTLEMENT_ID)
                .query(Long.class).single();
    }

    private long csmSettlementPostings() {
        return jdbc.sql("SELECT count(*) FROM postings WHERE account_id = ?").param(TechnicalAccounts.CSM_SETTLEMENT_ID)
                .query(Long.class).single();
    }

    private void assertBalances(UUID accountId, long booked, long available) {
        JsonNode account = http().get().uri("/v1/accounts/{id}", accountId).header(AUTHORIZATION, operator("op-1"))
                .retrieve().toEntity(JsonNode.class).getBody();
        assertThat(account.get("bookedBalanceMinor").asLong()).as("booked").isEqualTo(booked);
        assertThat(account.get("availableBalanceMinor").asLong()).as("available").isEqualTo(available);
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(status);
        assertThat(response.getBody().get("code").asString()).isEqualTo(code);
    }
}
