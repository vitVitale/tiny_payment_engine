package com.tpe.systemtests.localstack;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.systemtests.harness.CustomerScope;
import com.tpe.systemtests.harness.MockIssuer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * The compose stack's issuer mints customer-channel tokens as deploy/oauth2/config.json maps them (issue #63,
 * specs/00-engineering-baseline.md §12.1): the requested scopes and an {@code accounts} array.
 */
@EnabledIfSystemProperty(named = "tpe.local-stack", matches = "true",
        disabledReason = "needs the compose stack; run with -Dtpe.local-stack=true")
class MockIssuerIT {

    private static final String IBAN = "DE89370400440532013000";

    @Test
    void aCustomerTokenFromTheMockIssuerCarriesTheScopesAndTheAccountsArray() {
        var claims = MockIssuer.claims(MockIssuer.customer("customer-anna", IBAN,
                CustomerScope.PAYMENTS_WRITE, CustomerScope.PAYMENTS_READ, CustomerScope.VOP_VERIFY));

        assertThat(claims.get("sub").asString()).isEqualTo("customer-anna");
        assertThat(claims.get("client_id").asString()).isEqualTo(MockIssuer.CUSTOMER_CHANNEL);
        assertThat(claims.get("scope").asString()).isEqualTo("payments:write payments:read vop:verify");
        assertThat(claims.get("accounts").isArray()).isTrue();
        assertThat(claims.get("accounts").values()).extracting(node -> node.asString()).containsExactly(IBAN);
        assertThat(claims.has("roles")).isFalse();
    }
}
