package com.tpe.ledgerservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.contracts.TechnicalAccounts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Block and unblock per reason (specs/12-ledger-service.md §3.2, UC-10): who may touch which reason, the account's
 * status, revision and {@code accounts.events}, and the audit log.
 */
class BlockIT extends LedgerStack {

    private static final AtomicInteger CUSTOMERS = new AtomicInteger();
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    JdbcClient jdbc;

    @Test
    void LED_AC_13_aBackofficeOperatorCannotLiftASanctionsBlockRiskServiceAndAComplianceOfficerCan() {
        UUID byRisk = sanctioned();
        UUID byOfficer = sanctioned();

        var refused = unblock(operator("op-1"), byRisk, "SANCTIONS");

        assertProblem(refused, 403, "SANCTIONS_BLOCK_PROTECTED");
        assertThat(status(byRisk)).isEqualTo("BLOCKED");
        assertThat(audit(byRisk, "UNBLOCK")).isEmpty();
        assertThat(events(byRisk)).extracting(Event::type).containsExactly("AccountOpened", "AccountBlocked");

        var lifted = unblock(riskService(), byRisk, "SANCTIONS");
        var liftedByOfficer = unblock(complianceOfficer("officer-1"), byOfficer, "SANCTIONS");

        assertThat(lifted.getStatusCode().value()).isEqualTo(200);
        assertThat(lifted.getBody().get("status").asString()).isEqualTo("OPEN");
        assertThat(liftedByOfficer.getStatusCode().value()).isEqualTo(200);
        assertThat(status(byOfficer)).isEqualTo("OPEN");
    }

    @Test
    void aBackofficeOperatorCannotAddASanctionsBlockEither() {
        UUID accountId = openCustomer(customerId());

        assertProblem(block(operator("op-1"), accountId, "SANCTIONS_PROVISIONAL"), 403, "SANCTIONS_BLOCK_PROTECTED");
        assertThat(status(accountId)).isEqualTo("OPEN");
    }

    @Test
    void riskServiceAndComplianceOfficersCannotTouchOperatorBlocks() {
        UUID accountId = openCustomer(customerId());

        assertProblem(block(riskService(), accountId, "OPERATOR"), 403, "FORBIDDEN");
        assertProblem(block(complianceOfficer("officer-1"), accountId, "OPERATOR"), 403, "FORBIDDEN");
        assertThat(status(accountId)).isEqualTo("OPEN");
    }

    /** The block and unblock cases of LED-AC-18. */
    @Test
    void LED_AC_18_eachBlockAndUnblockWritesOneAuditRowWithTheActor() {
        UUID accountId = openCustomer(customerId());

        block(operator("operator-anna"), accountId, "OPERATOR", "fraud suspicion");
        block(riskService(), accountId, "SANCTIONS_PROVISIONAL");
        unblock(operator("operator-ben"), accountId, "OPERATOR");

        assertThat(audit(accountId, "BLOCK")).containsExactlyInAnyOrder(
                List.of("operator-anna", "OPERATOR", "fraud suspicion"),
                List.of("risk-service", "SANCTIONS_PROVISIONAL", ""));
        assertThat(audit(accountId, "UNBLOCK")).containsExactly(List.of("operator-ben", "OPERATOR", ""));
    }

    @Test
    void liftingOperatorWithSanctionsActiveLeavesTheAccountBlockedAndPublishesUnblockedWithStatusBlocked() {
        UUID accountId = openCustomer(customerId());
        block(operator("op-1"), accountId, "OPERATOR");
        block(riskService(), accountId, "SANCTIONS");

        var lifted = unblock(operator("op-1"), accountId, "OPERATOR");

        assertThat(lifted.getStatusCode().value()).isEqualTo(200);
        assertThat(lifted.getBody().get("status").asString()).isEqualTo("BLOCKED");
        Event event = events(accountId).getLast();
        assertThat(event.type()).isEqualTo("AccountUnblocked");
        assertThat(event.payload().get("liftedReason").asString()).isEqualTo("OPERATOR");
        assertThat(event.payload().get("account").get("status").asString()).isEqualTo("BLOCKED");
        assertThat(reasons(event.payload().get("account").get("activeBlockReasons"))).containsExactly("SANCTIONS");
    }

    @Test
    void sanctionsOverAnActiveProvisionalBlockLeavesExactlyOneActiveBlock() {
        UUID accountId = openCustomer(customerId());
        block(riskService(), accountId, "SANCTIONS_PROVISIONAL");

        var confirmed = block(riskService(), accountId, "SANCTIONS");

        assertThat(confirmed.getStatusCode().value()).isEqualTo(200);
        assertThat(reasons(confirmed.getBody().get("activeBlockReasons"))).containsExactly("SANCTIONS");
        assertThat(jdbc.sql("SELECT reason FROM account_blocks WHERE account_id = ? AND lifted_at IS NULL")
                .param(accountId).query(String.class).list()).containsExactly("SANCTIONS");
        assertThat(jdbc.sql("SELECT lifted_by FROM account_blocks WHERE account_id = ? AND reason = ?")
                .params(accountId, "SANCTIONS_PROVISIONAL").query(String.class).single()).isEqualTo("risk-service");
        Event event = events(accountId).getLast();
        assertThat(event.type()).isEqualTo("AccountBlocked");
        assertThat(event.payload().get("blockReason").asString()).isEqualTo("SANCTIONS");
        assertThat(reasons(event.payload().get("account").get("activeBlockReasons"))).containsExactly("SANCTIONS");
    }

    @Test
    void aRepeatedBlockWithTheSameReasonIs200PublishesNothingAndKeepsTheRevision() {
        UUID accountId = openCustomer(customerId());
        block(operator("op-1"), accountId, "OPERATOR");
        long revision = revision(accountId);

        var repeated = block(operator("op-2"), accountId, "OPERATOR");

        assertThat(repeated.getStatusCode().value()).isEqualTo(200);
        assertThat(repeated.getBody().get("status").asString()).isEqualTo("BLOCKED");
        assertThat(revision(accountId)).isEqualTo(revision);
        assertThat(events(accountId)).hasSize(2);
        assertThat(audit(accountId, "BLOCK")).hasSize(1);
    }

    @Test
    void liftingAReasonThatIsNotActiveIs200AndChangesNothing() {
        UUID accountId = openCustomer(customerId());

        var response = unblock(operator("op-1"), accountId, "OPERATOR");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("status").asString()).isEqualTo("OPEN");
        assertThat(events(accountId)).hasSize(1);
        assertThat(revision(accountId)).isEqualTo(1);
    }

    @Test
    void everyPublishedEventHasTheRevisionOneHigherThanThePrevious() {
        UUID accountId = openCustomer(customerId());
        block(operator("op-1"), accountId, "OPERATOR");
        block(riskService(), accountId, "SANCTIONS_PROVISIONAL");
        block(riskService(), accountId, "SANCTIONS");
        unblock(riskService(), accountId, "SANCTIONS");
        unblock(operator("op-1"), accountId, "OPERATOR");

        List<Event> events = events(accountId);

        assertThat(events).extracting(Event::type).containsExactly("AccountOpened", "AccountBlocked",
                "AccountBlocked", "AccountBlocked", "AccountUnblocked", "AccountUnblocked");
        assertThat(events).extracting(event -> event.payload().get("revision").asLong())
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(events.getLast().payload().get("account").get("status").asString()).isEqualTo("OPEN");
        assertThat(revision(accountId)).isEqualTo(6);
    }

    @Test
    void aClosedAccountCannotBeBlockedOrUnblocked() {
        UUID accountId = openCustomer(customerId());
        jdbc.sql("UPDATE accounts SET status = 'CLOSED', closed_at = now() WHERE account_id = ?").param(accountId)
                .update();

        assertProblem(block(operator("op-1"), accountId, "OPERATOR"), 422, "ACCOUNT_CLOSED");
        assertProblem(unblock(operator("op-1"), accountId, "OPERATOR"), 422, "ACCOUNT_CLOSED");
        assertThat(events(accountId)).hasSize(1);
        assertThat(audit(accountId, "BLOCK")).isEmpty();
        assertThat(audit(accountId, "UNBLOCK")).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM account_blocks WHERE account_id = ?").param(accountId)
                .query(Long.class).single()).isZero();
    }

    @Test
    void invalidRequestsAreRefusedAndChangeNothing() {
        UUID accountId = openCustomer(customerId());

        assertProblem(post(operator("op-1"), newIdempotencyKey(), accountId, "block", Map.of()), 400,
                "VALIDATION_FAILED");
        assertProblem(block(operator("op-1"), accountId, "HOLIDAY"), 400, "VALIDATION_FAILED");
        assertProblem(block(operator("op-1"), accountId, "OPERATOR", "x".repeat(501)), 400, "VALIDATION_FAILED");
        assertProblem(post(operator("op-1"), null, accountId, "block", Map.of("reason", "OPERATOR")), 400,
                "IDEMPOTENCY_KEY_MISSING");
        assertProblem(block(operator("op-1"), UUID.randomUUID(), "OPERATOR"), 422, "ACCOUNT_NOT_FOUND");
        assertProblem(block(operator("op-1"), TechnicalAccounts.FUNDING_ID, "OPERATOR"), 422,
                "ACCOUNT_NOT_CUSTOMER");
        assertProblem(block(null, accountId, "OPERATOR"), 401, "UNAUTHENTICATED");
        assertProblem(block(bearer(StaffTokens.serviceToken("vop-service", "vop:verify")), accountId, "OPERATOR"),
                403, "FORBIDDEN");
        assertThat(status(accountId)).isEqualTo("OPEN");
    }

    @Test
    void aBlockedAccountReadsWithItsBlockReasons() {
        UUID accountId = openCustomer(customerId());
        block(operator("op-1"), accountId, "OPERATOR");

        JsonNode account = http().get().uri("/v1/accounts/{id}", accountId).header(AUTHORIZATION, operator("op-1"))
                .retrieve().toEntity(JsonNode.class).getBody();

        assertThat(account.get("status").asString()).isEqualTo("BLOCKED");
        assertThat(reasons(account.get("activeBlockReasons"))).containsExactly("OPERATOR");
    }

    @Test
    void theCustomerLookupReturnsEveryAccountOfTheCustomerAndRequiresItsScope() {
        String customerId = customerId();
        UUID first = openCustomer(customerId);
        UUID second = openCustomer(customerId);
        openCustomer(customerId());
        block(operator("op-1"), second, "OPERATOR");

        var found = customerAccounts(bearer(StaffTokens.serviceToken("risk-service", "ledger:customer-accounts")),
                customerId);
        var unknown = customerAccounts(bearer(StaffTokens.serviceToken("risk-service", "ledger:customer-accounts")),
                customerId());

        assertThat(found.getStatusCode().value()).isEqualTo(200);
        assertThat(found.getBody().get("accounts"))
                .extracting(account -> List.of(account.get("accountId").asString(), account.get("status").asString()))
                .containsExactlyInAnyOrder(List.of(first.toString(), "OPEN"), List.of(second.toString(), "BLOCKED"));
        assertThat(unknown.getBody().get("accounts")).isEmpty();
        assertProblem(customerAccounts(operator("op-1"), customerId), 403, "FORBIDDEN");
        assertProblem(customerAccounts(riskService(), customerId), 403, "FORBIDDEN");
        assertProblem(customerAccounts(null, customerId), 401, "UNAUTHENTICATED");
    }

    /** An account with a {@code SANCTIONS} block added by a compliance officer. */
    private UUID sanctioned() {
        UUID accountId = openCustomer(customerId());
        var blocked = block(complianceOfficer("officer-2"), accountId, "SANCTIONS");
        assertThat(blocked.getStatusCode().value()).isEqualTo(200);
        return accountId;
    }

    private ResponseEntity<JsonNode> block(String authorization, UUID accountId, String reason) {
        return post(authorization, newIdempotencyKey(), accountId, "block", Map.of("reason", reason));
    }

    private ResponseEntity<JsonNode> block(String authorization, UUID accountId, String reason, String comment) {
        return post(authorization, newIdempotencyKey(), accountId, "block",
                Map.of("reason", reason, "comment", comment));
    }

    private ResponseEntity<JsonNode> unblock(String authorization, UUID accountId, String reason) {
        return post(authorization, newIdempotencyKey(), accountId, "unblock", Map.of("reason", reason));
    }

    private ResponseEntity<JsonNode> post(String authorization, String idempotencyKey, UUID accountId, String action,
            Map<String, ?> body) {
        var request = http().post().uri("/v1/accounts/{id}/" + action, accountId)
                .contentType(MediaType.APPLICATION_JSON).body(body);
        if (authorization != null) {
            request.header(AUTHORIZATION, authorization);
        }
        if (idempotencyKey != null) {
            request.header(IDEMPOTENCY_KEY, idempotencyKey);
        }
        return request.retrieve().toEntity(JsonNode.class);
    }

    private ResponseEntity<JsonNode> customerAccounts(String authorization, String customerId) {
        var request = http().get().uri("/v1/customers/{customerId}/accounts", customerId);
        if (authorization != null) {
            request.header(AUTHORIZATION, authorization);
        }
        return request.retrieve().toEntity(JsonNode.class);
    }

    private String status(UUID accountId) {
        return jdbc.sql("SELECT status FROM accounts WHERE account_id = ?").param(accountId).query(String.class)
                .single();
    }

    private long revision(UUID accountId) {
        return jdbc.sql("SELECT revision FROM accounts WHERE account_id = ?").param(accountId).query(Long.class)
                .single();
    }

    /** {@code [actor, reason, comment or ""]} of the account's audit rows of {@code action}. */
    private List<List<String>> audit(UUID accountId, String action) {
        return jdbc.sql("""
                SELECT actor, details ->> 'reason' AS reason, coalesce(details ->> 'comment', '') AS comment
                  FROM audit_log WHERE account_id = ? AND action = ? ORDER BY id""")
                .params(accountId, action)
                .query((rs, n) -> List.of(rs.getString("actor"), rs.getString("reason"), rs.getString("comment")))
                .list();
    }

    private List<Event> events(UUID accountId) {
        return jdbc.sql("SELECT message_type, payload::text AS payload FROM outbox WHERE message_key = ? ORDER BY id")
                .param(accountId.toString())
                .query((rs, n) -> new Event(rs.getString("message_type"), JSON.readTree(rs.getString("payload"))))
                .list();
    }

    private static List<String> reasons(JsonNode array) {
        List<String> reasons = new ArrayList<>();
        array.forEach(reason -> reasons.add(reason.asString()));
        return reasons;
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).isEqualTo(status);
        assertThat(response.getBody().get("code").asString()).isEqualTo(code);
    }

    private static String riskService() {
        return bearer(StaffTokens.serviceToken("risk-service", "ledger:sanctions-block"));
    }

    private static String complianceOfficer(String subject) {
        return bearer(StaffTokens.token(subject, "COMPLIANCE_OFFICER"));
    }

    private static String customerId() {
        return "BLOCK-" + UUID.randomUUID().toString().substring(0, 8) + "-" + CUSTOMERS.incrementAndGet();
    }

    private record Event(String type, JsonNode payload) {}
}
