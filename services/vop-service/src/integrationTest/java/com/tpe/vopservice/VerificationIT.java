package com.tpe.vopservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.vopservice.application.accounts.AccountHolderProjection;
import com.tpe.vopservice.domain.accounts.AccountHolder;
import com.tpe.vopservice.domain.accounts.AccountHolder.AccountStatus;
import com.tpe.vopservice.domain.accounts.AccountHolder.HolderType;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * {@code POST /v1/verifications} (specs/13-vop-service.md §3.1, §6.1, UC-02): own customers answered locally, other
 * banks through the counterparty port (no client yet: {@code NOT_POSSIBLE}), every valid request stored as a
 * reference and answered {@code 201}.
 */
class VerificationIT extends VopStack {

    private static final String DEBTOR = "DE36100100100000000001";
    /** Bank 20020020, PEERDEFFXXX in deploy/config/bank-directory.yml. */
    private static final String PEER_PAYEE = "DE07200200200123456789";

    @Autowired
    AccountHolderProjection projection;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    MeterRegistry meters;

    @Test
    void VOP_AC_01_anOwnCustomerWithTheExactHolderNameIsAMatchWithoutAnyHttpCall() {
        String payee = holder("Max Mustermann");
        double ownBefore = requests("REQUESTER", "OWN", "MATCH", "NONE");
        double remoteBefore = remoteRequests();

        ResponseEntity<JsonNode> response = verify(DEBTOR, payee, "Max Mustermann", null);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().get("result").asString()).isEqualTo("MATCH");
        assertThat(response.getBody().get("actualName").isNull()).isTrue();
        assertThat(response.getBody().get("respondedBy").asString()).isEqualTo("TPEBDEFFXXX");
        assertThat(response.getBody().get("vopReference").asString()).matches("VOP-[0-9A-HJKMNP-TV-Z]{26}");
        // answered by the own route: the counterparty port, the only way out, was not taken
        assertThat(requests("REQUESTER", "OWN", "MATCH", "NONE")).isEqualTo(ownBefore + 1);
        assertThat(remoteRequests()).isEqualTo(remoteBefore);
    }

    @Test
    void anOwnCustomerWithACloseNameGetsTheActualName() {
        String payee = holder("Anna Schmidt-Berg");

        ResponseEntity<JsonNode> response = verify(DEBTOR, payee, "Anna Schmitt", null);

        assertThat(response.getBody().get("result").asString()).isEqualTo("CLOSE_MATCH");
        assertThat(response.getBody().get("actualName").asString()).isEqualTo("Anna Schmidt-Berg");
    }

    @Test
    void anUnknownOwnIbanIsNotPossibleAndStillIssuesAReference() {
        ResponseEntity<JsonNode> response = verify(DEBTOR, Ibans.own(), "Max Mustermann", null);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().get("result").asString()).isEqualTo("NOT_POSSIBLE");
        assertThat(response.getBody().get("vopReference").isString()).isTrue();
    }

    @Test
    void aPayeeAtAnotherBankIsNotPossibleUntilTheCounterpartyClientExistsAndAReferenceIsIssued() {
        double before = requests("REQUESTER", "REMOTE", "NOT_POSSIBLE", "LOCAL");

        ResponseEntity<JsonNode> response = verify(DEBTOR, PEER_PAYEE, "Max Mustermann", null);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody().get("result").asString()).isEqualTo("NOT_POSSIBLE");
        assertThat(response.getBody().get("respondedBy").asString()).isEqualTo("PEERDEFFXXX");
        String reference = response.getBody().get("vopReference").asString();
        assertThat(jdbc.sql("SELECT count(*) FROM vop_results WHERE vop_reference = ?").param(reference)
                .query(Long.class).single()).isEqualTo(1);
        assertThat(requests("REQUESTER", "REMOTE", "NOT_POSSIBLE", "LOCAL")).isEqualTo(before + 1);
    }

    @Test
    void anUnknownPayeeBankIs422PayeeBankUnknown() {
        // bank code 37040044 is not in the directory
        ResponseEntity<JsonNode> response = verify(DEBTOR, "DE89370400440532013000", "Max Mustermann", null);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
        assertThat(response.getBody().get("code").asString()).isEqualTo("PAYEE_BANK_UNKNOWN");
    }

    @Test
    void theResultIsStoredForTheReferenceValidityWithTheValuesItWasIssuedFor() {
        String payee = holder("Max Mustermann");
        Instant now = CLOCK.instant();

        String reference = verify(DEBTOR, payee, "  Max  Mustermann ", null).getBody().get("vopReference").asString();

        Map<String, Object> row = jdbc.sql("""
                        SELECT debtor_iban, payee_iban, payee_name, result, actual_name, responded_by, created_at,
                               expires_at
                        FROM vop_results WHERE vop_reference = ?""")
                .param(reference).query().singleRow();
        assertThat(row.get("debtor_iban")).isEqualTo(DEBTOR);
        assertThat(row.get("payee_iban")).isEqualTo(payee);
        assertThat(row.get("payee_name")).isEqualTo("  Max  Mustermann ");
        assertThat(row.get("result")).isEqualTo("MATCH");
        assertThat(row.get("actual_name")).isNull();
        assertThat(row.get("responded_by")).isEqualTo("TPEBDEFFXXX");
        assertThat(((java.sql.Timestamp) row.get("created_at")).toInstant()).isEqualTo(now);
        assertThat(((java.sql.Timestamp) row.get("expires_at")).toInstant()).isEqualTo(now.plus(Duration.ofMinutes(30)));
    }

    @Test
    void invalidFieldsAre400ValidationFailed() {
        ResponseEntity<JsonNode> missing = verify(null, DEBTOR, " ", null);
        assertThat(missing.getStatusCode().value()).isEqualTo(400);
        assertThat(missing.getBody().get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(missing.getBody().get("errors").toString())
                .contains("{\"field\":\"debtorIban\",\"code\":\"REQUIRED\"}")
                .contains("{\"field\":\"payeeName\",\"code\":\"REQUIRED\"}");

        ResponseEntity<JsonNode> tooLong = verify(DEBTOR, PEER_PAYEE, "x".repeat(71), "x".repeat(36));
        assertThat(tooLong.getBody().get("errors").toString())
                .contains("{\"field\":\"payeeName\",\"code\":\"TOO_LONG\"}")
                .contains("{\"field\":\"payeeOrganisationId\",\"code\":\"TOO_LONG\"}");

        ResponseEntity<JsonNode> badIban = verify(DEBTOR, "DE00200200200123456789", "Max Mustermann", null);
        assertThat(badIban.getStatusCode().value()).isEqualTo(400);
        assertThat(badIban.getBody().get("errors").toString())
                .contains("{\"field\":\"payeeIban\",\"code\":\"INVALID_IBAN\"}");
    }

    private String holder(String name) {
        var holder = new AccountHolder(UUID.randomUUID(), Ibans.own(), HolderType.PERSON, name, null,
                AccountStatus.OPEN, 1);
        projection.apply(UUID.randomUUID(), holder);
        return holder.iban();
    }

    private ResponseEntity<JsonNode> verify(String debtor, String payee, String name, String organisationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("debtorIban", debtor);
        body.put("payeeIban", payee);
        body.put("payeeName", name);
        body.put("payeeOrganisationId", organisationId);
        return http().post().uri("/v1/verifications")
                .header("Content-Type", "application/json").body(body).retrieve().toEntity(JsonNode.class);
    }

    private double requests(String role, String route, String result, String cause) {
        Timer timer = meters.find("tpe.vop.requests")
                .tags("role", role, "route", route, "result", result, "cause", cause).timer();
        return timer == null ? 0 : timer.count();
    }

    private double remoteRequests() {
        return meters.find("tpe.vop.requests").tags("route", "REMOTE").timers().stream()
                .mapToDouble(Timer::count).sum();
    }
}
