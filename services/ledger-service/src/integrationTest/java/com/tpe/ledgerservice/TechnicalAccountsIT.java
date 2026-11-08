package com.tpe.ledgerservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.contracts.TechnicalAccounts;
import com.tpe.paymentdomain.Iban;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/** The seed migration's technical accounts (specs/12-ledger-service.md §5, ADR-005). */
class TechnicalAccountsIT extends LedgerStack {

    @Autowired
    JdbcClient jdbc;

    @Test
    void existAfterMigrationWithTheIdsOfTechnicalAccounts() {
        Map<UUID, String> codes = jdbc.sql("SELECT account_id, technical_code FROM accounts WHERE kind = 'TECHNICAL'")
                .query((rs, n) -> Map.entry(rs.getObject("account_id", UUID.class), rs.getString("technical_code")))
                .list().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertThat(codes).containsOnly(
                Map.entry(TechnicalAccounts.CSM_SETTLEMENT_ID, "CSM_SETTLEMENT"),
                Map.entry(TechnicalAccounts.FUNDING_ID, "FUNDING"));
    }

    @Test
    void haveDistinctValidIbansWithOurBankCode() {
        List<String> ibans = jdbc.sql("SELECT iban FROM accounts WHERE kind = 'TECHNICAL'").query(String.class).list();

        assertThat(ibans).hasSize(2).doesNotHaveDuplicates();
        assertThat(ibans).allSatisfy(iban -> assertThat(new Iban(iban).bankCode()).isEqualTo("10010010"));
    }

    /**
     * 12 §3.3: the Account API reads show a technical account with the balance derived from its postings (§6.3), not
     * its row's {@code booked_minor}, which stays 0.
     */
    @Test
    void areReadByTheAccountApiWithTheirDerivedBalance() {
        String iban = jdbc.sql("SELECT iban FROM accounts WHERE account_id = ?")
                .param(TechnicalAccounts.FUNDING_ID).query(String.class).single();
        UUID customer = openCustomer("CUST-TECH-" + UUID.randomUUID().toString().substring(0, 8));
        assertThat(fund(operator("op-1"), newIdempotencyKey(), customer, eur(2_500)).getStatusCode().value())
                .isEqualTo(200);
        long expected = jdbc.sql("""
                SELECT coalesce(sum(CASE side WHEN 'CR' THEN amount_minor ELSE -amount_minor END), 0)
                  FROM postings WHERE account_id = ?""").param(TechnicalAccounts.FUNDING_ID).query(Long.class).single();

        var byId = http().get().uri("/v1/accounts/{id}", TechnicalAccounts.FUNDING_ID)
                .header(AUTHORIZATION, operator("op-1"))
                .retrieve().toEntity(JsonNode.class);
        var byIban = http().get().uri("/v1/accounts?iban={iban}", iban)
                .header(AUTHORIZATION, operator("op-1"))
                .retrieve().toEntity(JsonNode.class);

        assertThat(byId.getStatusCode().value()).isEqualTo(200);
        assertThat(byId.getBody().get("kind").asString()).isEqualTo("TECHNICAL");
        assertThat(byId.getBody().has("customerId")).isFalse();
        assertThat(byId.getBody().has("holder")).isFalse();
        assertThat(expected).isNegative();
        assertThat(byId.getBody().get("bookedBalanceMinor").asLong()).isEqualTo(expected);
        assertThat(byId.getBody().get("availableBalanceMinor").asLong()).isEqualTo(expected);
        assertThat(byIban.getStatusCode().value()).isEqualTo(200);
        assertThat(byIban.getBody().get("accountId").asString()).isEqualTo(TechnicalAccounts.FUNDING_ID.toString());
        assertThat(jdbc.sql("SELECT booked_minor FROM accounts WHERE account_id = ?")
                .param(TechnicalAccounts.FUNDING_ID).query(Long.class).single()).isZero();
    }

    /** Only customer accounts reach the outbox: an opening publishes its own account, never a technical one. */
    @Test
    void areNeverPublishedToAccountsEvents() {
        String customerId = "CUST-TECH-" + UUID.randomUUID().toString().substring(0, 8);
        var opened = open(operator("op-1"), newIdempotencyKey(),
                Map.of("customerId", customerId, "holder", Map.of("type", "PERSON", "name", "Anna Schmidt")));
        assertThat(opened.getStatusCode().value()).isEqualTo(201);

        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE payload -> 'account' ->> 'customerId' = ?")
                .param(customerId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM outbox WHERE message_key IN (?, ?)")
                .params(TechnicalAccounts.CSM_SETTLEMENT_ID.toString(), TechnicalAccounts.FUNDING_ID.toString())
                .query(Long.class).single()).isZero();
    }
}
