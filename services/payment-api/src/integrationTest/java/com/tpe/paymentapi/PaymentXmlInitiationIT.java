package com.tpe.paymentapi;

import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.tpe.iso20022model.IsoCodec;
import com.tpe.iso20022model.XsdValidator;
import com.tpe.iso20022model.pain002v10.Document;
import com.tpe.paymentdomain.CommandId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code POST /v1/payments} with a {@code pain.001.001.09} (specs/10-payment-api.md §3.1, §3.5, §5.1, §5.2) and the
 * {@code pain.002 RJCT FF01} of a document that failed validation at
 * {@code GET /v1/payment-rejections/{rejectionId}/pain.002}, against WireMock as the orchestrator and vop-service.
 */
@ExtendWith(OutputCaptureExtension.class)
class PaymentXmlInitiationIT extends PaymentApiStack {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final IsoCodec CODEC = new IsoCodec();

    private static final String DEBTOR = "DE36100100100000000001";
    private static final String REFERENCE = "VOP-01JABCDEFGHJKMNPQRSTVW0001";
    private static final String WRITE = "payments:write";
    private static final String READ = "payments:read";
    private static final String PAYMENT = "0192f0e4-7b1c-7cc3-a1b2-000000000101";

    @BeforeEach
    void answers() {
        vopAnswers("check-valid");
        orchestratorAnswers("submit-created");
    }

    @Test
    void API_AC_02_aValidPain001IsSubmittedAsTheEquivalentJsonIs() {
        var json = http().post().uri("/v1/payments").contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.AUTHORIZATION, Tokens.customer(WRITE, DEBTOR))
                .header("Idempotency-Key", "key-json")
                .body(PaymentInitiationIT.payment()).retrieve().toEntity(JsonNode.class);
        ResponseEntity<JsonNode> xml = initiate("key-xml", Pain001Sample.valid());

        assertThat(json.getStatusCode().value()).as("%s", json.getBody()).isEqualTo(202);
        assertThat(xml.getStatusCode().value()).as("%s", xml.getBody()).isEqualTo(202);
        assertThat(xml.getHeaders().getLocation()).hasToString("/v1/payments/" + PAYMENT);
        assertThat(xml.getBody()).isEqualTo(json.getBody());
        List<LoggedRequest> submits = submits();
        assertThat(submits).hasSize(2);
        ObjectNode fromJson = (ObjectNode) JSON.readTree(submits.get(0).getBodyAsString());
        ObjectNode fromXml = (ObjectNode) JSON.readTree(submits.get(1).getBodyAsString());
        assertThat(fromXml.remove("commandId").asString())
                .isEqualTo(CommandId.derive(Tokens.CLIENT, "key-xml").value().toString());
        fromJson.remove("commandId");
        assertThat(fromXml).isEqualTo(fromJson);
    }

    @Test
    void aReplayThatDiffersOnlyInCommentsIsTheSamePayment() {
        ResponseEntity<JsonNode> first = initiate("key-comments", Pain001Sample.valid());
        orchestratorAnswers("submit-created", "status-settled");

        ResponseEntity<JsonNode> again = initiate("key-comments",
                Pain001Sample.valid().with("<GrpHdr>", "<GrpHdr><!-- sent again -->"));

        assertThat(first.getStatusCode().value()).as("%s", first.getBody()).isEqualTo(202);
        assertThat(again.getStatusCode().value()).as("%s", again.getBody()).isEqualTo(202);
        assertThat(again.getBody().get("paymentId").asString()).isEqualTo(PAYMENT);
        assertThat(again.getBody().get("status").asString()).isEqualTo("ACSC");
        assertThat(submits()).as("no second orchestrator payment").isEmpty();
    }

    /** UC-01 2a: the stored response, though the document asked for yesterday by then. */
    @Test
    void aReplayAfterMidnightIsTheSamePayment() {
        initiate("key-midnight", Pain001Sample.valid());
        orchestratorAnswers("submit-created", "status-settled");
        CLOCK.set(START.plus(java.time.Duration.ofDays(1)));

        ResponseEntity<JsonNode> again = initiate("key-midnight", Pain001Sample.valid());

        assertThat(again.getStatusCode().value()).as("%s", again.getBody()).isEqualTo(202);
        assertThat(again.getBody().get("paymentId").asString()).isEqualTo(PAYMENT);
        assertThat(submits()).isEmpty();
    }

    @Test
    void aNewDocumentForAnotherDayIsAnUnsupportedVariant() {
        ResponseEntity<JsonNode> response = initiate("key-tomorrow",
                Pain001Sample.valid().withValue("Dt", "2026-11-14", "2026-11-15"));

        assertProblem(response, 400, "UNSUPPORTED_MEDIA_TYPE_VARIANT");
        assertNothingAsked();
    }

    @Test
    void anotherDocumentWithTheSameKeyIsAReusedKey() {
        initiate("key-reused", Pain001Sample.valid());

        ResponseEntity<JsonNode> again = initiate("key-reused",
                Pain001Sample.valid().with(">123.45<", ">123.46<"));

        assertProblem(again, 422, "IDEMPOTENCY_KEY_REUSED");
    }

    /** API-AC-06, the XML half: a schema violation. */
    @Test
    void API_AC_06_aSchemaInvalidDocumentIsRejectedWithAnFf01Pain002() {
        ResponseEntity<JsonNode> response = initiate("key-api-ac-06",
                Pain001Sample.valid().with("Ccy=\"EUR\"", "Ccy=\"euro\""));

        assertProblem(response, 400, "VALIDATION_FAILED");
        assertThat(response.getBody().get("errors")).isNotEmpty().allSatisfy(error ->
                assertThat(error.get("code").asString()).startsWith("cvc-"));
        String document = assertFf01(response.getBody().get("rejectionId").asString());
        assertThat(document).doesNotContain("euro");
        assertNothingAsked();
    }

    /** API-AC-06, the XML half: a field rule of §5.2 on a schema-valid document. */
    @Test
    void API_AC_06_aFieldErrorOfASchemaValidDocumentIsRejectedWithAnFf01Pain002() {
        ResponseEntity<JsonNode> response = initiate("key-api-ac-06-field",
                Pain001Sample.valid().withValue("IBAN", "DE63200200200000000001", "DE00200200200000000001"));

        assertProblem(response, 400, "VALIDATION_FAILED");
        assertThat(response.getBody().get("errors")).isEqualTo(JSON.readTree("""
                [{"field":"creditor.iban","code":"INVALID_IBAN"}]"""));
        assertFf01(response.getBody().get("rejectionId").asString());
        assertNothingAsked();
    }

    @Test
    void anotherClientCannotReadTheRejection() {
        ResponseEntity<JsonNode> response = initiate("key-foreign-rejection",
                Pain001Sample.valid().with("Ccy=\"EUR\"", "Ccy=\"euro\""));
        String rejectionId = response.getBody().get("rejectionId").asString();

        ResponseEntity<JsonNode> foreign = http().get().uri("/v1/payment-rejections/{id}/pain.002", rejectionId)
                .header(HttpHeaders.AUTHORIZATION, Tokens.ofClient("web-banking", READ, DEBTOR))
                .retrieve().toEntity(JsonNode.class);

        assertProblem(foreign, 404, "REJECTION_NOT_FOUND");
    }

    @Test
    void anUnknownRejectionIsNotFound() {
        ResponseEntity<JsonNode> response = http().get()
                .uri("/v1/payment-rejections/{id}/pain.002", "0192f0e4-7b1c-7cc3-a1b2-000000000999")
                .header(HttpHeaders.AUTHORIZATION, Tokens.customer(READ, DEBTOR))
                .retrieve().toEntity(JsonNode.class);

        assertProblem(response, 404, "REJECTION_NOT_FOUND");
    }

    @Test
    void API_AC_11_aDocumentWithADoctypeIsRefusedWithoutResolvingItsEntities() {
        String entity = VOP.baseUrl() + "/xxe";
        Pain001Sample withDoctype = Pain001Sample.valid()
                .with("<Document ", "<!DOCTYPE Document [<!ENTITY e SYSTEM \"" + entity + "\">]>\n<Document ")
                .with("<Nm>Max Mustermann</Nm>", "<Nm>&e;</Nm>");

        ResponseEntity<JsonNode> response = initiate("key-api-ac-11", withDoctype);

        assertProblem(response, 400, "VALIDATION_FAILED");
        assertThat(response.getBody().get("errors")).singleElement()
                .satisfies(error -> assertThat(error.get("code").asString()).isEqualTo("DOCTYPE_DISALLOWED"));
        assertThat(VOP.findAll(anyRequestedFor(anyUrl()))).as("the external entity fetched").isEmpty();
        assertNothingAsked();
    }

    @Test
    void twoTransactionsAreAnUnsupportedVariant() {
        ResponseEntity<JsonNode> response = initiate("key-two", Pain001Sample.valid().withTwoTransactions());

        assertProblem(response, 400, "UNSUPPORTED_MEDIA_TYPE_VARIANT");
        assertThat(response.getBody().has("rejectionId")).isFalse();
        assertNothingAsked();
    }

    @Test
    void anotherPain001VersionIsAnUnsupportedVariant() {
        ResponseEntity<JsonNode> response = initiate("key-version",
                Pain001Sample.valid().with("pain.001.001.09", "pain.001.001.03"));

        assertProblem(response, 400, "UNSUPPORTED_MEDIA_TYPE_VARIANT");
        assertNothingAsked();
    }

    @Test
    void aDocumentWithoutTheVopReferenceHeaderIsVopRequired() {
        ResponseEntity<JsonNode> response = initiate(Tokens.customer(WRITE, DEBTOR), "key-no-reference",
                Pain001Sample.valid(), null);

        assertProblem(response, 422, "VOP_REQUIRED");
        assertThat(response.getBody().get("detail").asString()).isEqualTo("MISSING");
    }

    @Test
    void aDebtorAccountNotInTheTokenIsForbidden() {
        ResponseEntity<JsonNode> response = initiate(Tokens.customer(WRITE, "DE89370400440532013000"),
                "key-foreign", Pain001Sample.valid(), REFERENCE);

        assertProblem(response, 403, "DEBTOR_ACCOUNT_NOT_OWNED");
        assertNothingAsked();
    }

    @Test
    void documentsAreNeverLogged(CapturedOutput output) {
        initiate("key-logged", Pain001Sample.valid());
        initiate("key-logged-invalid",
                Pain001Sample.valid().withValue("Nm", "Max Mustermann", "Max Mustermann".repeat(10)));

        assertThat(output.getAll()).doesNotContain(DEBTOR, "Anna Schmidt", "Max Mustermann", "Invoice 42");
    }

    /** The rejection's document: schema-valid {@code RJCT FF01} of the sample's message. */
    private String assertFf01(String rejectionId) {
        ResponseEntity<String> document = http().get().uri("/v1/payment-rejections/{id}/pain.002", rejectionId)
                .header(HttpHeaders.AUTHORIZATION, Tokens.customer(READ, DEBTOR))
                .retrieve().toEntity(String.class);
        assertThat(document.getStatusCode().value()).as("%s", document.getBody()).isEqualTo(200);
        assertThat(document.getHeaders().getContentType().isCompatibleWith(MediaType.APPLICATION_XML)).isTrue();
        assertThat(new XsdValidator().validate(document.getBody())).isEmpty();
        var group = CODEC.unmarshal(document.getBody(), Document.class).getCstmrPmtStsRpt().getOrgnlGrpInfAndSts();
        assertThat(group.getOrgnlMsgId()).isEqualTo(Pain001Sample.MESSAGE_ID);
        assertThat(group.getGrpSts()).isEqualTo("RJCT");
        assertThat(group.getStsRsnInf()).singleElement()
                .satisfies(reason -> assertThat(reason.getRsn().getCd()).isEqualTo("FF01"));
        return document.getBody();
    }

    private ResponseEntity<JsonNode> initiate(String idempotencyKey, Pain001Sample document) {
        return initiate(Tokens.customer(WRITE, DEBTOR), idempotencyKey, document, REFERENCE);
    }

    private ResponseEntity<JsonNode> initiate(String authorization, String idempotencyKey, Pain001Sample document,
            String vopReference) {
        var request = http().post().uri("/v1/payments").contentType(MediaType.APPLICATION_XML)
                .header(HttpHeaders.AUTHORIZATION, authorization)
                .header("Idempotency-Key", idempotencyKey);
        if (vopReference != null) {
            request.header("VoP-Reference", vopReference);
        }
        return request.body(document.xml()).retrieve().toEntity(JsonNode.class);
    }

    private static List<LoggedRequest> submits() {
        return ORCHESTRATOR.findAll(postRequestedFor(urlEqualTo("/v1/payments")));
    }

    private static void assertNothingAsked() {
        assertThat(VOP.findAll(anyRequestedFor(anyUrl()))).as("vop-service asked").isEmpty();
        assertThat(ORCHESTRATOR.findAll(anyRequestedFor(anyUrl()))).as("orchestrator asked").isEmpty();
    }

    private static void assertProblem(ResponseEntity<JsonNode> response, int status, String code) {
        assertThat(response.getStatusCode().value()).as("%s", response.getBody()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().get("code").asString()).isEqualTo(code);
    }
}
