package com.tpe.ledgerservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.JsonNode;

/**
 * Profile {@code prod} has no funding endpoint (specs/12-ledger-service.md §3.2, UC-10 3a). A context of its own; the
 * startup refusal with {@code tpe.ledger.funding.enabled=true} is {@code FundingGuardTest}.
 */
@ActiveProfiles(profiles = "prod", inheritProfiles = false)
class ProdProfileFundingIT extends LedgerStack {

    @Test
    void LED_AC_15_theFundingEndpointIs404InProfileProd() {
        UUID accountId = openCustomer("PROD-" + UUID.randomUUID().toString().substring(0, 8));

        var response = fund(operator("op-1"), newIdempotencyKey(), accountId, eur(100_000));

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(http().get().uri("/v1/accounts/{id}", accountId).header(AUTHORIZATION, operator("op-1"))
                .retrieve().toEntity(JsonNode.class)
                .getBody().get("bookedBalanceMinor").asLong()).isZero();
    }
}
