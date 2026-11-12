package com.tpe.vopservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.vopservice.application.accounts.AccountHolderProjection;
import com.tpe.vopservice.domain.accounts.AccountHolder;
import com.tpe.vopservice.domain.accounts.AccountHolder.AccountStatus;
import com.tpe.vopservice.domain.accounts.AccountHolder.HolderType;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /v1/verifications/{vopReference}/check} (specs/13-vop-service.md §3.1, UC-01 4, UC-02 7): payment-api
 * accepts a reference only for the debtor IBAN, payee IBAN and payee name it was issued for, before it expires.
 */
class ReferenceCheckIT extends VopStack {

    private static final String DEBTOR = "DE36100100100000000001";
    private static final String OTHER = "DE89370400440532013000";

    @Autowired
    AccountHolderProjection projection;

    @Test
    void theValuesTheReferenceWasIssuedForAreValidWithItsResult() {
        String payee = holder("Anna Schmidt-Berg");
        String reference = issue(payee, "Anna Schmitt");

        ResponseEntity<JsonNode> response = check(reference, DEBTOR, payee, " Anna  Schmitt ");

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().toString()).isEqualTo("{\"valid\":true,\"result\":\"CLOSE_MATCH\"}");
    }

    @Test
    void VOP_AC_03_theCheckFailsMismatchIfDebtorIbanPayeeIbanOrPayeeNameDiffer() {
        String payee = holder("Max Mustermann");
        String reference = issue(payee, "Max Mustermann");

        assertThat(problem(check(reference, OTHER, payee, "Max Mustermann"))).isEqualTo("MISMATCH");
        assertThat(problem(check(reference, DEBTOR, OTHER, "Max Mustermann"))).isEqualTo("MISMATCH");
        assertThat(problem(check(reference, DEBTOR, payee, "Erika Mustermann"))).isEqualTo("MISMATCH");
    }

    @Test
    void VOP_AC_03_theCheckFailsExpiredAfterTheValidity() {
        String payee = holder("Max Mustermann");
        String reference = issue(payee, "Max Mustermann");

        CLOCK.advance(Duration.ofMinutes(30).minusMillis(1));
        assertThat(check(reference, DEBTOR, payee, "Max Mustermann").getBody().get("valid").asBoolean()).isTrue();
        CLOCK.advance(Duration.ofMillis(1));
        assertThat(check(reference, DEBTOR, payee, "Max Mustermann").getBody().toString())
                .isEqualTo("{\"valid\":false,\"problem\":\"EXPIRED\"}");
    }

    @Test
    void VOP_AC_03_theCheckFailsUnknownForAMadeUpReference() {
        assertThat(problem(check("VOP-01JABCDEFGHJKMNPQRSTVWXYZ0", DEBTOR, OTHER, "Max Mustermann")))
                .isEqualTo("UNKNOWN");
        assertThat(problem(check("anything", DEBTOR, OTHER, "Max Mustermann"))).isEqualTo("UNKNOWN");
    }

    @Test
    void aPayeeNameDifferingOnlyInCaseFailsTheCheckWithMismatch() {
        String payee = holder("Max Mustermann");
        String reference = issue(payee, "Max Mustermann");

        assertThat(problem(check(reference, DEBTOR, payee, "max mustermann"))).isEqualTo("MISMATCH");
    }

    @Test
    void anInvalidBodyIs400ValidationFailed() {
        ResponseEntity<JsonNode> response = check("VOP-X", null, "DE00200200200123456789", "");

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("errors").toString())
                .contains("{\"field\":\"payeeIban\",\"code\":\"INVALID_IBAN\"}");

        ResponseEntity<JsonNode> missing = check("VOP-X", null, OTHER, "");
        assertThat(missing.getBody().get("errors").toString())
                .contains("{\"field\":\"debtorIban\",\"code\":\"REQUIRED\"}")
                .contains("{\"field\":\"payeeName\",\"code\":\"REQUIRED\"}");
    }

    private String holder(String name) {
        var holder = new AccountHolder(UUID.randomUUID(), Ibans.own(), HolderType.PERSON, name, null,
                AccountStatus.OPEN, 1);
        projection.apply(UUID.randomUUID(), holder);
        return holder.iban();
    }

    private String issue(String payee, String name) {
        Map<String, Object> body = Map.of("debtorIban", DEBTOR, "payeeIban", payee, "payeeName", name);
        ResponseEntity<JsonNode> response = http().post().uri("/v1/verifications")
                .header("Content-Type", "application/json").body(body).retrieve().toEntity(JsonNode.class);
        assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(201);
        return response.getBody().get("vopReference").asString();
    }

    private ResponseEntity<JsonNode> check(String reference, String debtor, String payee, String name) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("debtorIban", debtor);
        body.put("payeeIban", payee);
        body.put("payeeName", name);
        return http().post().uri("/v1/verifications/{reference}/check", reference)
                .header("Content-Type", "application/json").body(body).retrieve().toEntity(JsonNode.class);
    }

    private static String problem(ResponseEntity<JsonNode> response) {
        assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
        assertThat(response.getBody().get("valid").asBoolean()).isFalse();
        assertThat(response.getBody().has("result")).isFalse();
        return response.getBody().get("problem").asString();
    }
}
