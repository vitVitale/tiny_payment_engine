package com.tpe.ledgerservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tpe.contracts.AccountOpened;
import com.tpe.contracts.AccountStatus;
import com.tpe.contracts.ContractJson;
import com.tpe.contracts.HolderType;
import com.tpe.messaging.MessageHeaders;
import com.tpe.paymentdomain.Iban;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * The Account API's open and reads (specs/12-ledger-service.md §3.2, UC-10 1, 2, 4). The open part of LED-AC-17 (the
 * hold and pending-credit parts are in HoldIT and PendingCreditIT) and the open case of LED-AC-18 (fund, block,
 * unblock and close: FundingIT, BlockIT, CloseIT) are covered here.
 */
class AccountApiIT extends LedgerStack {

    private static final AtomicInteger CUSTOMERS = new AtomicInteger();

    @Autowired
    JdbcClient jdbc;

    @Test
    void LED_AC_01_openingReturnsAValidIbanWithOurBankCodeAndPublishesAccountOpenedWithRevision1() {
        String customerId = customerId();

        var response = open(operator("op-1"), person(customerId));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        JsonNode body = response.getBody();
        UUID accountId = UUID.fromString(body.get("accountId").asString());
        Iban iban = new Iban(body.get("iban").asString());
        assertThat(iban.bankCode()).isEqualTo("10010010");
        assertThat(iban.countryCode()).isEqualTo("DE");
        assertThat(body.get("status").asString()).isEqualTo("OPEN");
        assertThat(body.get("bookedBalanceMinor").asLong()).isZero();
        assertThat(body.get("availableBalanceMinor").asLong()).isZero();
        assertThat(body.get("currency").asString()).isEqualTo("EUR");

        ConsumerRecord<String, String> record = accountEvent(accountId);
        assertThat(header(record, MessageHeaders.MESSAGE_TYPE)).isEqualTo("AccountOpened");
        AccountOpened opened = new ContractJson().read(record.value(), AccountOpened.class);
        assertThat(opened.accountId()).isEqualTo(accountId);
        assertThat(opened.revision()).isEqualTo(1L);
        assertThat(opened.account().iban()).isEqualTo(iban);
        assertThat(opened.account().customerId()).isEqualTo(customerId);
        assertThat(opened.account().status()).isEqualTo(AccountStatus.OPEN);
        assertThat(opened.account().activeBlockReasons()).isEmpty();
        assertThat(opened.account().holder().type()).isEqualTo(HolderType.PERSON);
        assertThat(opened.account().holder().dateOfBirth()).isEqualTo(LocalDate.parse("1985-04-12"));
        assertThat(opened.account().openedAt()).isEqualTo(Instant.parse(body.get("openedAt").asString()))
                .isEqualTo(CLOCK.instant());
    }

    @Test
    void LED_AC_17_openingWithoutCustomerIdIsRefusedWith422CustomerIdMissing() {
        for (String missing : new String[] {null, "", "  "}) {
            var request = new HashMap<>(person("unused"));
            request.put("customerId", missing);

            var response = open(operator("op-1"), request);

            assertThat(response.getStatusCode().value()).isEqualTo(422);
            assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
            assertThat(response.getBody().get("code").asString()).isEqualTo("CUSTOMER_ID_MISSING");
        }
    }

    @Test
    void aMalformedCustomerIdIs400ValidationFailedWithInvalidCustomerId() {
        var response = open(operator("op-1"), person("CUST 000123"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(errors(response)).containsExactly(Map.entry("customerId", "INVALID_CUSTOMER_ID"));
    }

    @Test
    void anOrganisationWithoutOrganisationIdOrWithADateOfBirthIsRefusedWith400() {
        var withoutOrganisationId = open(operator("op-1"), Map.of("customerId", customerId(),
                "holder", Map.of("type", "ORGANISATION", "name", "ACME GmbH", "countryOfResidence", "DE")));
        var withDateOfBirth = open(operator("op-1"), Map.of("customerId", customerId(),
                "holder", Map.of("type", "ORGANISATION", "name", "ACME GmbH", "organisationId", "529900T8BM49AURSDO55",
                        "dateOfBirth", "1985-04-12")));

        assertThat(withoutOrganisationId.getStatusCode().value()).isEqualTo(400);
        assertThat(errors(withoutOrganisationId)).containsExactly(Map.entry("holder.organisationId", "REQUIRED"));
        assertThat(withDateOfBirth.getStatusCode().value()).isEqualTo(400);
        assertThat(errors(withDateOfBirth)).containsExactly(Map.entry("holder.dateOfBirth", "NOT_ALLOWED"));
    }

    @Test
    void anOrganisationWithOrganisationIdIsOpened() {
        var response = open(operator("op-1"), Map.of("customerId", customerId(),
                "holder", Map.of("type", "ORGANISATION", "name", "ACME GmbH", "organisationId", "529900T8BM49AURSDO55")));

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().get("holder").get("organisationId").asString()).isEqualTo("529900T8BM49AURSDO55");
        assertThat(response.getBody().get("holder").has("dateOfBirth")).isFalse();
    }

    @Test
    void anUnreadableBodyIs400ValidationFailed() {
        var response = http().post().uri("/v1/accounts")
                .header(AUTHORIZATION, operator("op-1"))
                .header(IDEMPOTENCY_KEY, newIdempotencyKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"customerId\": ")
                .retrieve().toEntity(JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    /** Internal REST request bodies ignore unknown fields (specs/00-engineering-baseline.md §2). */
    @Test
    void unknownFieldsOfTheRequestAreIgnored() {
        var request = new HashMap<>(person(customerId()));
        request.put("addedByANewerCaller", true);

        assertThat(open(operator("op-1"), request).getStatusCode().value()).isEqualTo(201);
    }

    @Test
    void LED_AC_18_openingWritesOneAuditRowWithTheOperatorAsActor() {
        var response = open(operator("operator-anna"), person(customerId()));
        UUID accountId = UUID.fromString(response.getBody().get("accountId").asString());

        var rows = jdbc.sql("SELECT actor, action, at FROM audit_log WHERE account_id = ?").param(accountId)
                .query((rs, n) -> List.of(rs.getString("actor"), rs.getString("action"),
                        rs.getObject("at", OffsetDateTime.class).toInstant()))
                .list();
        assertThat(rows).containsExactly(List.of("operator-anna", "OPEN",
                Instant.parse(response.getBody().get("openedAt").asString())));
    }

    /** The account, its audit row and its event commit together: a failing outbox insert leaves none of them. */
    @Test
    void aFailureAtTheOutboxLeavesNeitherAccountNorAuditRow() {
        String customerId = "FAIL-OUTBOX-" + customerId();
        jdbc.sql("""
                CREATE OR REPLACE FUNCTION fail_outbox() RETURNS trigger AS $$
                BEGIN
                  IF NEW.payload -> 'account' ->> 'customerId' LIKE 'FAIL-OUTBOX-%' THEN
                    RAISE EXCEPTION 'injected outbox failure';
                  END IF;
                  RETURN NEW;
                END $$ LANGUAGE plpgsql""").update();
        jdbc.sql("CREATE TRIGGER fail_outbox BEFORE INSERT ON outbox FOR EACH ROW EXECUTE FUNCTION fail_outbox()")
                .update();
        try {
            var response = open(operator("op-1"), person(customerId));

            assertThat(response.getStatusCode().value()).isEqualTo(500);
        } finally {
            jdbc.sql("DROP TRIGGER fail_outbox ON outbox").update();
        }
        assertThat(jdbc.sql("SELECT count(*) FROM accounts WHERE customer_id = ?").param(customerId)
                .query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM audit_log WHERE details ->> 'customerId' = ?").param(customerId)
                .query(Long.class).single()).isZero();
    }

    @Test
    void readsByIdAndByIbanReturnTheAccount() {
        var opened = open(operator("op-1"), person(customerId())).getBody();

        var byId = http().get().uri("/v1/accounts/{id}", opened.get("accountId").asString())
                .header(AUTHORIZATION, operator("op-2"))
                .retrieve().toEntity(JsonNode.class);
        var byIban = http().get().uri("/v1/accounts?iban={iban}", opened.get("iban").asString())
                .header(AUTHORIZATION, operator("op-2"))
                .retrieve().toEntity(JsonNode.class);

        assertThat(byId.getStatusCode().value()).isEqualTo(200);
        assertThat(byId.getBody()).isEqualTo(opened);
        assertThat(byIban.getStatusCode().value()).isEqualTo(200);
        assertThat(byIban.getBody()).isEqualTo(opened);
        assertThat(opened.get("holder").get("name").asString()).isEqualTo("Anna Schmidt");
        assertThat(opened.get("pendingCreditsMinor").asLong()).isZero();
        assertThat(opened.has("closedAt")).isFalse();
    }

    @Test
    void anUnknownAccountIs404AccountNotFound() {
        var byId = http().get().uri("/v1/accounts/{id}", UUID.randomUUID())
                .header(AUTHORIZATION, operator("op-1"))
                .retrieve().toEntity(JsonNode.class);
        var byIban = http().get().uri("/v1/accounts?iban={iban}", "DE89370400440532013000")
                .header(AUTHORIZATION, operator("op-1"))
                .retrieve().toEntity(JsonNode.class);

        assertThat(byId.getStatusCode().value()).isEqualTo(404);
        assertThat(byId.getBody().get("code").asString()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(byIban.getStatusCode().value()).isEqualTo(404);
        assertThat(byIban.getBody().get("code").asString()).isEqualTo("ACCOUNT_NOT_FOUND");
    }

    @Test
    void aMalformedIbanOrAccountIdIs400ValidationFailed() {
        var byIban = http().get().uri("/v1/accounts?iban={iban}", "DE00370400440532013000")
                .header(AUTHORIZATION, operator("op-1"))
                .retrieve().toEntity(JsonNode.class);
        var byId = http().get().uri("/v1/accounts/{id}", "not-a-uuid")
                .header(AUTHORIZATION, operator("op-1"))
                .retrieve().toEntity(JsonNode.class);

        assertThat(byIban.getStatusCode().value()).isEqualTo(400);
        assertThat(errors(byIban)).containsExactly(Map.entry("iban", "INVALID_IBAN"));
        assertThat(byId.getStatusCode().value()).isEqualTo(400);
        assertThat(byId.getBody().get("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void withoutAValidTokenEveryEndpointIs401Unauthenticated() {
        // a real signature over other claims
        String[] parts = StaffTokens.token("op-1", "COMPLIANCE_OFFICER").split("\\.");
        String granted = StaffTokens.token("op-1", "BACKOFFICE_OPERATOR").split("\\.")[1];
        String forged = bearer(parts[0] + "." + granted + "." + parts[2]);

        assertUnauthenticated(open(null, person(customerId())));
        assertUnauthenticated(open(forged, person(customerId())));
        assertUnauthenticated(http().get().uri("/v1/accounts/{id}", UUID.randomUUID())
                .retrieve().toEntity(JsonNode.class));
        assertUnauthenticated(http().get().uri("/v1/accounts?iban={iban}", "DE89370400440532013000")
                .retrieve().toEntity(JsonNode.class));
    }

    @Test
    void aTokenWithoutBackofficeOperatorIs403Forbidden() {
        String compliance = bearer(StaffTokens.token("officer-1", "COMPLIANCE_OFFICER"));
        String customerId = customerId();

        assertForbidden(open(compliance, person(customerId)));
        assertForbidden(http().get().uri("/v1/accounts/{id}", UUID.randomUUID()).header(AUTHORIZATION, compliance)
                .retrieve().toEntity(JsonNode.class));
        assertForbidden(http().get().uri("/v1/accounts?iban={iban}", "DE89370400440532013000")
                .header(AUTHORIZATION, compliance)
                .retrieve().toEntity(JsonNode.class));
        assertThat(jdbc.sql("SELECT count(*) FROM accounts WHERE customer_id = ?").param(customerId)
                .query(Long.class).single()).isZero();
    }

    private static void assertUnauthenticated(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getHeaders().getFirst("WWW-Authenticate")).startsWith("Bearer");
        assertProblem(response, "UNAUTHENTICATED");
    }

    private static void assertForbidden(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertProblem(response, "FORBIDDEN");
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, String code) {
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().get("code").asString()).isEqualTo(code);
        assertThat(response.getBody().get("status").asInt()).isEqualTo(response.getStatusCode().value());
        assertThat(response.getBody().get("instance").asString()).startsWith("/v1/accounts");
    }

    private ResponseEntity<JsonNode> open(String authorization, Map<String, ?> body) {
        return open(authorization, newIdempotencyKey(), body);
    }

    private static Map<String, Object> person(String customerId) {
        return Map.of("customerId", customerId,
                "holder", Map.of("type", "PERSON", "name", "Anna Schmidt", "dateOfBirth", "1985-04-12",
                        "countryOfResidence", "DE"));
    }

    private static String customerId() {
        return "CUST-" + UUID.randomUUID().toString().substring(0, 8) + "-" + CUSTOMERS.incrementAndGet();
    }

    private static List<Map.Entry<String, String>> errors(ResponseEntity<JsonNode> response) {
        return response.getBody().get("errors").valueStream()
                .map(error -> Map.entry(error.get("field").asString(), error.get("code").asString()))
                .toList();
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    /** The record of {@code accountId} on {@code accounts.events}, read from the start of the topic. */
    private static ConsumerRecord<String, String> accountEvent(UUID accountId) {
        var properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "account-api-it-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        try (var consumer = new KafkaConsumer<>(properties, new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of("accounts.events"));
            var found = new ArrayList<ConsumerRecord<String, String>>();
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(record -> {
                    if (record.key().equals(accountId.toString())) {
                        found.add(record);
                    }
                });
                return !found.isEmpty();
            });
            return found.getFirst();
        }
    }
}
