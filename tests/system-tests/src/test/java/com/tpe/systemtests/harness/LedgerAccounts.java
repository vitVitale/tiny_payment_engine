package com.tpe.systemtests.harness;

import com.tpe.contracts.BlockReason;
import java.net.URI;
import java.util.Map;
import java.util.UUID;

/**
 * The ledger account API as the system tests call it (specs/12-ledger-service.md §3.2): open, fund, read, block,
 * unblock, close. Its pact with ledger-service is P-10 ({@code LedgerServicePactTest}, specs/20-testing-strategy.md
 * §10.1), so a call the provider no longer answers this way fails a PR rather than the nightly run.
 */
public final class LedgerAccounts {

    private final JsonHttp http;

    public LedgerAccounts(URI baseUrl) {
        this.http = new JsonHttp(baseUrl);
    }

    /** {@code POST /v1/accounts}: a {@code PERSON} account for {@code customer}, resident in DE. */
    public Response open(String authorization, TestData.Customer customer) {
        return http.post(authorization, "/v1/accounts", Map.of("customerId", customer.customerId(),
                "holder", Map.of("type", "PERSON", "name", customer.name(), "countryOfResidence", "DE")));
    }

    /** {@code POST /v1/accounts/{id}/funding} in EUR (test funding, profile {@code local}). */
    public Response fund(String authorization, UUID account, long amountMinor) {
        return http.post(authorization, path(account, "/funding"),
                Map.of("amountMinor", amountMinor, "currency", "EUR"));
    }

    /** {@code GET /v1/accounts/{id}}. */
    public Response account(String authorization, UUID account) {
        return http.get(authorization, path(account, ""));
    }

    /** {@code POST /v1/accounts/{id}/block} for {@code reason}. */
    public Response block(String authorization, UUID account, BlockReason reason) {
        return http.post(authorization, path(account, "/block"), Map.of("reason", reason.name()));
    }

    /** {@code POST /v1/accounts/{id}/unblock} for {@code reason}. */
    public Response unblock(String authorization, UUID account, BlockReason reason) {
        return http.post(authorization, path(account, "/unblock"), Map.of("reason", reason.name()));
    }

    /** {@code POST /v1/accounts/{id}/close} with an empty body. */
    public Response close(String authorization, UUID account) {
        return http.post(authorization, path(account, "/close"), Map.of());
    }

    private static String path(UUID account, String action) {
        return "/v1/accounts/" + account + action;
    }
}
