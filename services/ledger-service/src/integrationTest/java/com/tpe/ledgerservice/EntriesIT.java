package com.tpe.ledgerservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.contracts.TechnicalAccounts;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** {@code GET /v1/accounts/{accountId}/entries} (specs/12-ledger-service.md §3.2, UC-10 4). */
class EntriesIT extends LedgerStack {

    private static final Instant T0 = Instant.parse("2026-02-01T00:00:00Z");

    @Autowired
    JdbcClient jdbc;

    @Test
    void paginationReturnsEveryPostingExactlyOnceNewestFirst() {
        UUID accountId = openCustomer("ENTRIES-" + UUID.randomUUID().toString().substring(0, 8));
        int count = 230;
        insertFundings(accountId, count);

        List<Long> amounts = new ArrayList<>();
        List<Integer> pageSizes = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = entries(accountId, cursor == null ? "" : "?cursor=" + cursor).getBody();
            pageSizes.add(page.get("postings").size());
            page.get("postings").forEach(posting -> amounts.add(posting.get("amountMinor").asLong()));
            cursor = page.has("nextCursor") ? page.get("nextCursor").asString() : null;
        } while (cursor != null);

        assertThat(pageSizes).containsExactly(100, 100, 30);
        // the i-th funding has amount i, so newest first is count, count − 1, …, 1
        List<Long> expected = new ArrayList<>();
        for (long amount = count; amount >= 1; amount--) {
            expected.add(amount);
        }
        assertThat(amounts).containsExactlyElementsOf(expected);
    }

    @Test
    void fromIsInclusiveAndToExclusive() {
        UUID accountId = openCustomer("ENTRIES-" + UUID.randomUUID().toString().substring(0, 8));
        insertFundings(accountId, 5);

        // funding i was booked at T0 + i minutes
        JsonNode page = entries(accountId, "?from=" + T0.plusSeconds(120) + "&to=" + T0.plusSeconds(240)).getBody();

        assertThat(page.get("postings")).extracting(posting -> posting.get("amountMinor").asLong())
                .containsExactly(3L, 2L);
    }

    @Test
    void anAccountWithoutPostingsHasAnEmptyFeed() {
        UUID accountId = openCustomer("ENTRIES-" + UUID.randomUUID().toString().substring(0, 8));

        JsonNode page = entries(accountId, "").getBody();

        assertThat(page.get("postings")).isEmpty();
        assertThat(page.has("nextCursor")).isFalse();
    }

    @Test
    void aCursorTheServiceDidNotIssueIs400() {
        UUID accountId = openCustomer("ENTRIES-" + UUID.randomUUID().toString().substring(0, 8));

        var garbage = entries(accountId, "?cursor=not-a-cursor");
        var badTime = entries(accountId, "?from=yesterday");

        assertThat(garbage.getStatusCode().value()).isEqualTo(400);
        assertThat(garbage.getBody().get("errors").get(0).get("code").asString()).isEqualTo("INVALID_CURSOR");
        assertThat(badTime.getStatusCode().value()).isEqualTo(400);
        assertThat(badTime.getBody().get("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void unknownAndTechnicalAccountsAreRefused() {
        var unknown = entries(UUID.randomUUID(), "");
        var technical = entries(TechnicalAccounts.FUNDING_ID, "");

        assertThat(unknown.getStatusCode().value()).isEqualTo(404);
        assertThat(unknown.getBody().get("code").asString()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(technical.getStatusCode().value()).isEqualTo(422);
        assertThat(technical.getBody().get("code").asString()).isEqualTo("ACCOUNT_NOT_CUSTOMER");
    }

    /**
     * {@code count} balanced FUNDING entries in one statement each, the i-th crediting {@code i} at T0 + i minutes,
     * in posting-id order.
     */
    private void insertFundings(UUID accountId, int count) {
        for (int i = 1; i <= count; i++) {
            UUID entryId = UUID.randomUUID();
            var at = T0.plusSeconds(60L * i).atOffset(ZoneOffset.UTC);
            jdbc.sql("""
                    WITH entry AS (
                      INSERT INTO journal_entries (entry_id, entry_type, created_at) VALUES (:entry, 'FUNDING', :at)
                      RETURNING entry_id)
                    INSERT INTO postings (entry_id, account_id, side, amount_minor, created_at)
                    SELECT entry_id, :funding, 'DR', :amount, :at FROM entry
                    UNION ALL
                    SELECT entry_id, :account, 'CR', :amount, :at FROM entry""")
                    .param("entry", entryId)
                    .param("at", at)
                    .param("funding", TechnicalAccounts.FUNDING_ID)
                    .param("account", accountId)
                    .param("amount", (long) i)
                    .update();
        }
    }

    private ResponseEntity<JsonNode> entries(UUID accountId, String query) {
        return http().get().uri("/v1/accounts/" + accountId + "/entries" + query)
                .header(AUTHORIZATION, operator("op-1"))
                .retrieve().toEntity(JsonNode.class);
    }
}
