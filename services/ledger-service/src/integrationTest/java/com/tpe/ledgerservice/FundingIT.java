package com.tpe.ledgerservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.contracts.TechnicalAccounts;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * Test funding through the posting engine (specs/12-ledger-service.md §3.2, §6.2, UC-10 3–4): a balanced entry
 * Dr FUNDING / Cr customer, the customer row written version-checked with optimistic retries, the FUNDING row never
 * written.
 */
class FundingIT extends LedgerStack {

    private static final AtomicInteger CUSTOMERS = new AtomicInteger();

    @Autowired
    JdbcClient jdbc;

    @Test
    void afterFundingBookedAndAvailableEqualTheAmountAndTheEntriesShowTheCreditPosting() {
        UUID accountId = openCustomer(customerId());

        var funded = fund(operator("op-1"), newIdempotencyKey(), accountId, eur(100_000));

        assertThat(funded.getStatusCode().value()).isEqualTo(200);
        JsonNode body = funded.getBody();
        assertThat(body.get("accountId").asString()).isEqualTo(accountId.toString());
        assertThat(body.get("amountMinor").asLong()).isEqualTo(100_000);
        assertThat(body.get("bookedBalanceMinor").asLong()).isEqualTo(100_000);
        assertThat(body.get("availableBalanceMinor").asLong()).isEqualTo(100_000);

        JsonNode account = get("/v1/accounts/{id}", accountId).getBody();
        assertThat(account.get("bookedBalanceMinor").asLong()).isEqualTo(100_000);
        assertThat(account.get("availableBalanceMinor").asLong()).isEqualTo(100_000);

        JsonNode entries = get("/v1/accounts/{id}/entries", accountId).getBody();
        assertThat(entries.get("postings")).hasSize(1);
        JsonNode posting = entries.get("postings").get(0);
        assertThat(posting.get("entryId").asString()).isEqualTo(body.get("journalEntryId").asString());
        assertThat(posting.get("entryType").asString()).isEqualTo("FUNDING");
        assertThat(posting.get("side").asString()).isEqualTo("CR");
        assertThat(posting.get("amountMinor").asLong()).isEqualTo(100_000);
        assertThat(posting.get("createdAt").asString()).isEqualTo(body.get("fundedAt").asString());
        assertThat(entries.has("nextCursor")).isFalse();
    }

    @Test
    void theEntryDebitsTheFundingAccountAndCreditsTheCustomer() {
        UUID accountId = openCustomer(customerId());

        var funded = fund(operator("op-1"), newIdempotencyKey(), accountId, eur(2_500));

        UUID entryId = UUID.fromString(funded.getBody().get("journalEntryId").asString());
        assertThat(jdbc.sql("SELECT entry_type FROM journal_entries WHERE entry_id = ?").param(entryId)
                .query(String.class).single()).isEqualTo("FUNDING");
        assertThat(jdbc.sql("SELECT account_id, side, amount_minor FROM postings WHERE entry_id = ? ORDER BY side")
                .param(entryId)
                .query((rs, n) -> List.of(rs.getObject("account_id", UUID.class), rs.getString("side"),
                        rs.getLong("amount_minor")))
                .list())
                .containsExactly(List.of(accountId, "CR", 2_500L), List.of(TechnicalAccounts.FUNDING_ID, "DR", 2_500L));
    }

    @Test
    void postingsToTheFundingAccountNeverChangeItsRow() {
        Map<String, Object> before = fundingRow();
        UUID accountId = openCustomer(customerId());

        fund(operator("op-1"), newIdempotencyKey(), accountId, eur(700));
        fund(operator("op-1"), newIdempotencyKey(), accountId, eur(300));

        assertThat(fundingRow()).isEqualTo(before);
    }

    /** The fund case of LED-AC-18; open, block, unblock and close are in AccountApiIT, BlockIT and CloseIT. */
    @Test
    void LED_AC_18_fundingWritesOneAuditRowWithTheOperatorAsActor() {
        UUID accountId = openCustomer(customerId());

        var funded = fund(operator("operator-ben"), newIdempotencyKey(), accountId, eur(4_200));

        var rows = jdbc.sql("""
                SELECT actor, at, details ->> 'amountMinor' AS amount, details ->> 'journalEntryId' AS entry
                  FROM audit_log WHERE account_id = ? AND action = 'FUND'""")
                .param(accountId)
                .query((rs, n) -> List.of(rs.getString("actor"), rs.getObject("at", OffsetDateTime.class).toInstant(),
                        rs.getString("amount"), rs.getString("entry")))
                .list();
        assertThat(rows).containsExactly(List.of("operator-ben", Instant.parse(funded.getBody().get("fundedAt").asString()),
                "4200", funded.getBody().get("journalEntryId").asString()));
    }

    /** A request with five writers on one account loses at most four times, so five attempts always suffice. */
    @Test
    void concurrentFundingsOfOneAccountAllSucceedAndTheBalanceIsTheirSum() throws Exception {
        UUID accountId = openCustomer(customerId());
        int writers = 5;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ResponseEntity<JsonNode>>> responses = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(writers)) {
            for (int i = 1; i <= writers; i++) {
                long amount = i * 1_000L;
                responses.add(pool.submit(() -> {
                    start.await();
                    return fund(operator("op-1"), newIdempotencyKey(), accountId, eur(amount));
                }));
            }
            start.countDown();
            for (var response : responses) {
                assertThat(response.get().getStatusCode().value()).isEqualTo(200);
            }
        }

        assertThat(get("/v1/accounts/{id}", accountId).getBody().get("bookedBalanceMinor").asLong())
                .isEqualTo(15_000);
        assertThat(jdbc.sql("SELECT count(*) FROM postings WHERE account_id = ?").param(accountId)
                .query(Long.class).single()).isEqualTo(writers);
        assertThat(jdbc.sql("SELECT version FROM accounts WHERE account_id = ?").param(accountId)
                .query(Long.class).single()).isEqualTo(writers);
    }

    /**
     * A trigger skips every update of this account's row, so each version-checked write finds nothing to update: all
     * five attempts conflict. Nothing is written and the key is not stored, so the same request succeeds later.
     */
    @Test
    void exhaustedRetriesAre409ConcurrentModificationAndChangeNothing() {
        String customerId = "NO-UPDATE-" + customerId();
        UUID accountId = openCustomer(customerId);
        String key = newIdempotencyKey();
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION skip_account_update() RETURNS trigger AS $$
                BEGIN
                  IF OLD.customer_id LIKE 'NO-UPDATE-%' THEN
                    RETURN NULL;
                  END IF;
                  RETURN NEW;
                END $$ LANGUAGE plpgsql""").update();
        jdbc.sql("CREATE TRIGGER skip_account_update BEFORE UPDATE ON accounts FOR EACH ROW "
                + "EXECUTE FUNCTION skip_account_update()").update();
        ResponseEntity<JsonNode> refused;
        try {
            refused = fund(operator("op-1"), key, accountId, eur(1_000));
        } finally {
            jdbc.sql("DROP TRIGGER skip_account_update ON accounts").update();
        }

        assertThat(refused.getStatusCode().value()).isEqualTo(409);
        assertThat(refused.getBody().get("code").asString()).isEqualTo("CONCURRENT_MODIFICATION");
        assertThat(jdbc.sql("SELECT count(*) FROM postings WHERE account_id = ?").param(accountId)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE account_id = ? AND action = 'FUND'")
                .param(accountId).query(Long.class).single()).isZero();

        var retried = fund(operator("op-1"), key, accountId, eur(1_000));
        assertThat(retried.getStatusCode().value()).isEqualTo(200);
        assertThat(retried.getBody().get("bookedBalanceMinor").asLong()).isEqualTo(1_000);
    }

    @Test
    void aRetryWithTheSameKeyReplaysTheResponseAndBooksOnce() {
        UUID accountId = openCustomer(customerId());
        String key = newIdempotencyKey();

        var first = fund(operator("op-1"), key, accountId, eur(800));
        var replayed = fund(operator("op-1"), key, accountId, eur(800));
        var conflicting = fund(operator("op-1"), key, accountId, eur(801));

        assertThat(replayed.getStatusCode().value()).isEqualTo(200);
        assertThat(replayed.getBody()).isEqualTo(first.getBody());
        assertThat(conflicting.getStatusCode().value()).isEqualTo(409);
        assertThat(conflicting.getBody().get("code").asString()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(get("/v1/accounts/{id}", accountId).getBody().get("bookedBalanceMinor").asLong()).isEqualTo(800);
    }

    @Test
    void refusedFundingsChangeNothing() {
        UUID accountId = openCustomer(customerId());

        var dollars = fund(operator("op-1"), newIdempotencyKey(), accountId,
                Map.of("amountMinor", 100, "currency", "USD"));
        var zero = fund(operator("op-1"), newIdempotencyKey(), accountId, eur(0));
        var missing = fund(operator("op-1"), newIdempotencyKey(), accountId, Map.of());
        var unknown = fund(operator("op-1"), newIdempotencyKey(), UUID.randomUUID(), eur(100));
        var technical = fund(operator("op-1"), newIdempotencyKey(), TechnicalAccounts.CSM_SETTLEMENT_ID, eur(100));
        var noKey = fund(operator("op-1"), null, accountId, eur(100));

        assertProblem(dollars, 422, "CURRENCY_NOT_SUPPORTED");
        assertProblem(zero, 400, "VALIDATION_FAILED");
        assertThat(zero.getBody().get("errors").get(0).get("code").asString()).isEqualTo("INVALID_AMOUNT");
        assertProblem(missing, 400, "VALIDATION_FAILED");
        assertThat(missing.getBody().get("errors")).hasSize(2);
        // 422 on a change, 404 only on reads (12 §3.4)
        assertProblem(unknown, 422, "ACCOUNT_NOT_FOUND");
        assertProblem(technical, 422, "ACCOUNT_NOT_CUSTOMER");
        assertProblem(noKey, 400, "IDEMPOTENCY_KEY_MISSING");
        assertThat(jdbc.sql("SELECT count(*) FROM postings WHERE account_id = ?").param(accountId)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE account_id = ? AND action = 'FUND'")
                .param(accountId).query(Long.class).single()).isZero();
    }

    @Test
    void fundingNeedsABackofficeOperator() {
        UUID accountId = openCustomer(customerId());

        var anonymous = fund(null, newIdempotencyKey(), accountId, eur(100));
        var compliance = fund(bearer(StaffTokens.token("officer-1", "COMPLIANCE_OFFICER")), newIdempotencyKey(),
                accountId, eur(100));

        assertProblem(anonymous, 401, "UNAUTHENTICATED");
        assertProblem(compliance, 403, "FORBIDDEN");
    }

    private Map<String, Object> fundingRow() {
        return jdbc.sql("SELECT * FROM accounts WHERE account_id = ?").param(TechnicalAccounts.FUNDING_ID)
                .query().singleRow();
    }

    private ResponseEntity<JsonNode> get(String uri, Object... variables) {
        return http().get().uri(uri, variables).header(AUTHORIZATION, operator("op-1"))
                .retrieve().toEntity(JsonNode.class);
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().get("code").asString()).isEqualTo(code);
    }

    private static String customerId() {
        return "FUND-" + UUID.randomUUID().toString().substring(0, 8) + "-" + CUSTOMERS.incrementAndGet();
    }
}
