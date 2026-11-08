package com.tpe.ledgerservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.contracts.TechnicalAccounts;
import com.tpe.ledgerservice.application.posting.TechnicalAccountSnapshots;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/**
 * Technical-account balances from snapshots and postings (specs/12-ledger-service.md §6.3, ADR-005). A snapshot only
 * reaches postings older than {@link TechnicalAccountSnapshots#SETTLE_TIME}, so these tests move the shared clock, and
 * put it back after. The snapshot job also runs in the background (profile test: every second), as one more writer.
 */
class SnapshotIT extends LedgerStack {

    private static final UUID CSM = TechnicalAccounts.CSM_SETTLEMENT_ID;
    private static final Duration PAST_SETTLE_TIME = TechnicalAccountSnapshots.SETTLE_TIME.plusSeconds(1);
    private static final AtomicInteger CUSTOMERS = new AtomicInteger();

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    TechnicalAccountSnapshots snapshots;

    @Autowired
    PlatformTransactionManager transactionManager;

    private Instant start;

    @BeforeEach
    void rememberTheClock() {
        start = CLOCK.instant();
    }

    @AfterEach
    void putTheClockBack() {
        CLOCK.set(start);
    }

    @Test
    void LED_AC_11_theDerivedCsmBalanceEqualsTheSumOfItsPostingsAfterSnapshotsTakenWhilePostingsArrive()
            throws Exception {
        AtomicBoolean posting = new AtomicBoolean(true);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Future<Integer> postings = pool.submit(() -> {
                int count = 0;
                while (posting.get() && count < 200) {
                    confirmedIncomingPayment(100 + count);
                    count++;
                }
                return count;
            });
            try {
                // 5 s of clock per 300 ms: a posting's transaction, a few ms long, sees the clock move far less than the
                // settle time, as in production; after 12 steps the snapshots reach postings of this test
                for (int i = 0; i < 20; i++) {
                    CLOCK.advance(Duration.ofSeconds(5));
                    snapshot(CSM);
                    Thread.sleep(300);
                }
            } finally {
                posting.set(false);
            }
            assertThat(postings.get()).isPositive();
        }
        CLOCK.advance(PAST_SETTLE_TIME);
        snapshot(CSM);

        assertThat(snapshotCount(CSM)).isPositive();
        assertEverySnapshotIsTheSumOfItsPostings(CSM);
        assertThat(derivedBalance(CSM)).isEqualTo(sumOfPostings(CSM));
    }

    /**
     * A transaction holds a posting with a lower id open while later postings commit and snapshots are taken: none of
     * them reaches past it before it is older than the settle time, so once it commits it is counted.
     */
    @Test
    void aPostingCommittedLateWithALowerIdThanCommittedOnesIsStillCounted() throws Exception {
        confirmedIncomingPayment(50);
        CLOCK.advance(PAST_SETTLE_TIME);
        snapshot(CSM);
        long before = latestBoundary(CSM);

        long heldPostingId;
        try (Connection held = dataSource.getConnection()) {
            held.setAutoCommit(false);
            heldPostingId = insertEntry(held, 777);
            confirmedIncomingPayment(60);
            long laterPostingId = lastPosting(CSM);
            assertThat(laterPostingId).isGreaterThan(heldPostingId);

            snapshot(CSM);
            CLOCK.advance(Duration.ofSeconds(30));
            snapshot(CSM);
            assertThat(latestBoundary(CSM)).isEqualTo(before).isLessThan(heldPostingId);

            held.commit();
        }

        assertThat(derivedBalance(CSM)).isEqualTo(sumOfPostings(CSM));
        CLOCK.advance(PAST_SETTLE_TIME);
        snapshot(CSM);
        assertThat(latestBoundary(CSM)).isGreaterThan(heldPostingId);
        assertEverySnapshotIsTheSumOfItsPostings(CSM);
        assertThat(derivedBalance(CSM)).isEqualTo(sumOfPostings(CSM));
    }

    @Test
    void withNoSnapshotTheDerivedBalanceIsTheFullSum() {
        fund(operator("op-1"), newIdempotencyKey(), openCustomer(customerId()), eur(1_234));
        jdbc.sql("DELETE FROM balance_snapshots WHERE account_id = ?").param(TechnicalAccounts.FUNDING_ID).update();

        assertThat(derivedBalance(TechnicalAccounts.FUNDING_ID)).isEqualTo(sumOfPostings(TechnicalAccounts.FUNDING_ID))
                .isNegative();
    }

    /** An idle account's latest snapshot is confirmed as of the run, so the job is not due for it before the interval. */
    @Test
    void aRunWithoutNewSettledPostingsConfirmsTheLatestSnapshot() {
        confirmedIncomingPayment(80);
        CLOCK.advance(PAST_SETTLE_TIME);
        snapshot(CSM);
        long boundary = latestBoundary(CSM);
        long rows = snapshotCount(CSM);

        CLOCK.advance(Duration.ofMinutes(5));
        snapshot(CSM);

        assertThat(latestBoundary(CSM)).isEqualTo(boundary);
        assertThat(snapshotCount(CSM)).isEqualTo(rows);
        assertThat(jdbc.sql("""
                SELECT taken_at FROM balance_snapshots WHERE account_id = ? AND up_to_posting_id = ?""")
                .params(CSM, boundary).query(java.time.OffsetDateTime.class).single().toInstant())
                .isEqualTo(CLOCK.instant().truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
    }

    /** Several writers at once: each boundary is written once, and every snapshot holds its postings' sum. */
    @Test
    void writersRacingForTheSameAccountNeverWriteASnapshotTwice() throws Exception {
        confirmedIncomingPayment(70);
        CLOCK.advance(PAST_SETTLE_TIME);
        int writers = 8;
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> runs = new ArrayList<>();
        try (var pool = Executors.newFixedThreadPool(writers)) {
            for (int i = 0; i < writers; i++) {
                runs.add(pool.submit(() -> {
                    go.await();
                    snapshot(CSM);
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> run : runs) {
                run.get();
            }
        }

        assertThat(jdbc.sql("SELECT count(*) FROM balance_snapshots WHERE account_id = ? AND up_to_posting_id = ?")
                .params(CSM, lastPosting(CSM)).query(Long.class).single()).isOne();
        assertEverySnapshotIsTheSumOfItsPostings(CSM);
    }

    /** One run of the job's handler for {@code accountId}, in its own transaction as the worker runs it. */
    private void snapshot(UUID accountId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> snapshots.handle(accountId));
    }

    /** An incoming payment booked to a new customer: Dr CSM settlement account / Cr payee. */
    private void confirmedIncomingPayment(long amountMinor) {
        UUID accountId = openCustomer(customerId());
        String iban = jdbc.sql("SELECT iban FROM accounts WHERE account_id = ?").param(accountId)
                .query(String.class).single();
        UUID id = UUID.randomUUID();
        assertThat(post("/v1/pending-credits", Map.of("pendingCreditId", id.toString(), "creditorIban", iban,
                "amountMinor", amountMinor, "currency", "EUR")).getStatusCode().value()).isEqualTo(201);
        assertThat(post("/v1/pending-credits/" + id + "/confirm", Map.of()).getStatusCode().value()).isEqualTo(200);
    }

    /** A balanced entry Dr CSM / Cr FUNDING inserted in {@code held}, uncommitted; returns its CSM posting's id. */
    private long insertEntry(Connection held, long amountMinor) throws Exception {
        UUID entryId = UUID.randomUUID();
        var now = java.sql.Timestamp.from(CLOCK.instant());
        try (PreparedStatement entry = held.prepareStatement(
                "INSERT INTO journal_entries (entry_id, entry_type, created_at) VALUES (?, 'PAYMENT_IN', ?)")) {
            entry.setObject(1, entryId);
            entry.setTimestamp(2, now);
            entry.executeUpdate();
        }
        long csmPosting = 0;
        for (var leg : List.of(Map.entry(CSM, "DR"), Map.entry(TechnicalAccounts.FUNDING_ID, "CR"))) {
            try (PreparedStatement posting = held.prepareStatement("""
                    INSERT INTO postings (entry_id, account_id, side, amount_minor, created_at)
                    VALUES (?, ?, ?, ?, ?) RETURNING posting_id""")) {
                posting.setObject(1, entryId);
                posting.setObject(2, leg.getKey());
                posting.setString(3, leg.getValue());
                posting.setLong(4, amountMinor);
                posting.setTimestamp(5, now);
                try (var rs = posting.executeQuery()) {
                    rs.next();
                    if (leg.getKey().equals(CSM)) {
                        csmPosting = rs.getLong(1);
                    }
                }
            }
        }
        return csmPosting;
    }

    private org.springframework.http.ResponseEntity<JsonNode> post(String uri, Map<String, ?> body) {
        return http().post().uri(uri).contentType(MediaType.APPLICATION_JSON).header(IDEMPOTENCY_KEY,
                newIdempotencyKey()).body(body).retrieve().toEntity(JsonNode.class);
    }

    private long derivedBalance(UUID accountId) {
        JsonNode account = http().get().uri("/v1/accounts/{id}", accountId).header(AUTHORIZATION, operator("op-1"))
                .retrieve().toEntity(JsonNode.class).getBody();
        return account.get("bookedBalanceMinor").asLong();
    }

    private long sumOfPostings(UUID accountId) {
        return jdbc.sql("""
                SELECT coalesce(sum(CASE side WHEN 'CR' THEN amount_minor ELSE -amount_minor END), 0)
                  FROM postings WHERE account_id = ?""").param(accountId).query(Long.class).single();
    }

    private void assertEverySnapshotIsTheSumOfItsPostings(UUID accountId) {
        List<long[]> mismatches = jdbc.sql("""
                SELECT s.up_to_posting_id, s.balance_minor,
                       (SELECT coalesce(sum(CASE side WHEN 'CR' THEN amount_minor ELSE -amount_minor END), 0)
                          FROM postings p WHERE p.account_id = s.account_id AND p.posting_id <= s.up_to_posting_id)
                  FROM balance_snapshots s WHERE s.account_id = ?""")
                .param(accountId)
                .query((rs, n) -> new long[] {rs.getLong(1), rs.getLong(2), rs.getLong(3)})
                .list().stream().filter(row -> row[1] != row[2]).toList();
        assertThat(mismatches).as("snapshots whose balance is not the sum of their postings").isEmpty();
    }

    private long snapshotCount(UUID accountId) {
        return jdbc.sql("SELECT count(*) FROM balance_snapshots WHERE account_id = ?").param(accountId)
                .query(Long.class).single();
    }

    private long latestBoundary(UUID accountId) {
        return jdbc.sql("SELECT coalesce(max(up_to_posting_id), 0) FROM balance_snapshots WHERE account_id = ?")
                .param(accountId).query(Long.class).single();
    }

    private long lastPosting(UUID accountId) {
        return jdbc.sql("SELECT max(posting_id) FROM postings WHERE account_id = ?").param(accountId)
                .query(Long.class).single();
    }

    private static String customerId() {
        return "SNAP-" + UUID.randomUUID().toString().substring(0, 8) + "-" + CUSTOMERS.incrementAndGet();
    }
}
