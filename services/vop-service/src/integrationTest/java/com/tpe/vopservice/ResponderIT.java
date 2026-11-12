package com.tpe.vopservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.vopservice.application.accounts.AccountHolderProjection;
import com.tpe.vopservice.domain.accounts.AccountHolder;
import com.tpe.vopservice.domain.accounts.AccountHolder.AccountStatus;
import com.tpe.vopservice.domain.accounts.AccountHolder.HolderType;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * The scheme endpoint {@code POST /vop/v1/verifications} (specs/13-vop-service.md §3.2, §6.2, UC-03) against the
 * projection, the bank directory of deploy/config/bank-directory.yml and {@code responder_audit}.
 */
class ResponderIT extends VopStack {

    private static final String PEER = "PEERDEFFXXX";
    private static final String LEI = "529900T8BM49AURSDO55";

    @Autowired
    AccountHolderProjection projection;

    @Autowired
    JdbcClient jdbc;

    @Test
    void VOP_AC_05_anUnknownOrClosedIbanIsNotPossibleAndABlockedAccountIsAnsweredNormally() {
        String unknown = Ibans.own();
        AccountHolder closed = holder(HolderType.PERSON, "Anna Schmidt", null, AccountStatus.CLOSED);
        AccountHolder blocked = holder(HolderType.PERSON, "Anna Schmidt", null, AccountStatus.BLOCKED);

        assertThat(verify(unknown, "Anna Schmidt", null).getBody().get("result").asString()).isEqualTo("NOT_POSSIBLE");
        assertThat(verify(closed.iban(), "Anna Schmidt", null).getBody().get("result").asString())
                .isEqualTo("NOT_POSSIBLE");
        assertThat(verify(blocked.iban(), "Anna Schmidt", null).getBody().get("result").asString())
                .isEqualTo("MATCH");
        assertThat(verify(blocked.iban(), "Peter Wagner", null).getBody().get("result").asString())
                .isEqualTo("NO_MATCH");
    }

    @Test
    void aCloseMatchReturnsTheStoredHolderNameAndEchoesTheRequestId() {
        AccountHolder holder = holder(HolderType.PERSON, "Anna Schmidt-Berg", null, AccountStatus.OPEN);
        String requestId = UUID.randomUUID().toString();

        ResponseEntity<JsonNode> response = post(request(requestId, PEER, holder.iban(), "Anna Schmitt", null));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("requestId").asString()).isEqualTo(requestId);
        assertThat(response.getBody().get("result").asString()).isEqualTo("CLOSE_MATCH");
        assertThat(response.getBody().get("actualName").asString()).isEqualTo("Anna Schmidt-Berg");
    }

    @Test
    void anIbanInPaperFormatFindsTheHolder() {
        AccountHolder holder = holder(HolderType.PERSON, "Anna Schmidt", null, AccountStatus.OPEN);
        String paper = holder.iban().replaceAll("(.{4})", "$1 ").strip().toLowerCase();

        ResponseEntity<JsonNode> response = verify(paper, "Anna Schmidt", null);

        assertThat(response.getBody().get("result").asString()).isEqualTo("MATCH");
        assertThat(response.getBody().get("actualName").isNull()).isTrue();
    }

    @Test
    void VOP_AC_06_theOrganisationIdTakesPrecedenceOverTheName() {
        AccountHolder acme = holder(HolderType.ORGANISATION, "Acme GmbH", LEI, AccountStatus.OPEN);

        assertThat(verify(acme.iban(), "Globex Corporation", LEI).getBody().get("result").asString())
                .isEqualTo("MATCH");
        assertThat(verify(acme.iban(), "Acme GmbH", "5493001KJTIIGC8Y1R12").getBody().get("result").asString())
                .isEqualTo("NO_MATCH");
    }

    @Test
    void anUnknownRequestingPspIs401UnknownParticipant() {
        AccountHolder holder = holder(HolderType.PERSON, "Anna Schmidt", null, AccountStatus.OPEN);

        for (String bic : new String[] {"UNKNDEFFXXX", "not a bic", "", null}) {
            ResponseEntity<JsonNode> response = post(request("r-1", bic, holder.iban(), "Anna Schmidt", null));

            assertThat(response.getStatusCode().value()).as(bic).isEqualTo(401);
            assertThat(response.getHeaders().getContentType()).hasToString("application/problem+json");
            assertThat(response.getBody().get("code").asString()).isEqualTo("UNKNOWN_PARTICIPANT");
        }
        // a participant known by its institution (8 characters) is fine
        assertThat(post(request("r-2", "PEERDEFF", holder.iban(), "Anna Schmidt", null)).getStatusCode().value())
                .isEqualTo(200);
    }

    @Test
    void anUnknownParticipantIsRefusedBeforeTheRequestIsValidated() {
        ResponseEntity<JsonNode> response = post(request(null, "UNKNDEFFXXX", "garbage", null, null));

        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }

    @Test
    void invalidFieldsAre400ValidationFailed() {
        ResponseEntity<JsonNode> response = post(
                request("x".repeat(65), PEER, "DE00100100100000000001", " ", "x".repeat(36)));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().get("errors").toString())
                .contains("{\"field\":\"requestId\",\"code\":\"TOO_LONG\"}")
                .contains("{\"field\":\"iban\",\"code\":\"INVALID_IBAN\"}")
                .contains("{\"field\":\"name\",\"code\":\"REQUIRED\"}")
                .contains("{\"field\":\"organisationId\",\"code\":\"TOO_LONG\"}");

        ResponseEntity<JsonNode> missing = post(request(null, PEER, null, "x".repeat(141), null));
        assertThat(missing.getBody().get("errors").toString())
                .contains("{\"field\":\"requestId\",\"code\":\"REQUIRED\"}")
                .contains("{\"field\":\"iban\",\"code\":\"REQUIRED\"}")
                .contains("{\"field\":\"name\",\"code\":\"TOO_LONG\"}");
    }

    @Test
    void anUnreadableBodyIs400ValidationFailed() {
        var response = http().post().uri("/vop/v1/verifications")
                .header("Content-Type", "application/json").body("{").retrieve().toEntity(JsonNode.class);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(response.getBody().get("code").asString()).isEqualTo("VALIDATION_FAILED");
    }

    /** The audit half of 13 §9's retention criterion, and the UC-03 postcondition. */
    @Test
    void everyAnswerIsAuditedWithPepperedHashesOnlyAndThePepperVersion() throws Exception {
        AccountHolder holder = holder(HolderType.PERSON, "Anna Schmidt-Berg", null, AccountStatus.OPEN);
        String requestId = UUID.randomUUID().toString();

        post(request(requestId, PEER, holder.iban(), "Anna Schmitt", null));

        Map<String, Object> row = jdbc.sql("""
                        SELECT requesting_psp_bic, iban_hash, name_hash, pepper_version, result, at
                        FROM responder_audit WHERE request_id = ?""")
                .param(requestId).query().singleRow();
        assertThat(row.get("requesting_psp_bic")).isEqualTo(PEER);
        assertThat(row.get("iban_hash")).isEqualTo(hmac(holder.iban()));
        assertThat(row.get("name_hash")).isEqualTo(hmac("Anna Schmitt"));
        assertThat(((Number) row.get("pepper_version")).intValue()).isEqualTo(ACTIVE_PEPPER_VERSION);
        assertThat(row.get("result")).isEqualTo("CLOSE_MATCH");
        assertThat(row.get("at")).isNotNull();
        String wholeRow = jdbc.sql("SELECT a::text FROM responder_audit a WHERE request_id = ?")
                .param(requestId).query(String.class).single().toLowerCase();
        assertThat(wholeRow).doesNotContain(holder.iban().toLowerCase(), "schmitt", "schmidt", "anna");
    }

    @Test
    void aRefusedRequestIsNotAudited() {
        String requestId = UUID.randomUUID().toString();

        post(request(requestId, "UNKNDEFFXXX", Ibans.own(), "Anna Schmidt", null));

        assertThat(jdbc.sql("SELECT count(*) FROM responder_audit WHERE request_id = ?").param(requestId)
                .query(Long.class).single()).isZero();
    }

    private AccountHolder holder(HolderType type, String name, String organisationId, AccountStatus status) {
        var holder = new AccountHolder(UUID.randomUUID(), Ibans.own(), type, name, organisationId, status, 1);
        projection.apply(UUID.randomUUID(), holder);
        return holder;
    }

    private ResponseEntity<JsonNode> verify(String iban, String name, String organisationId) {
        ResponseEntity<JsonNode> response = post(request(UUID.randomUUID().toString(), PEER, iban, name,
                organisationId));
        assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(200);
        return response;
    }

    private static Map<String, Object> request(String requestId, String bic, String iban, String name,
            String organisationId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("requestId", requestId);
        body.put("requestingPspBic", bic);
        body.put("iban", iban);
        body.put("name", name);
        body.put("organisationId", organisationId);
        return body;
    }

    private ResponseEntity<JsonNode> post(Map<String, Object> body) {
        return http().post().uri("/vop/v1/verifications")
                .header("Content-Type", "application/json").body(body).retrieve().toEntity(JsonNode.class);
    }

    private static String hmac(String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(ACTIVE_PEPPER.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }
}
