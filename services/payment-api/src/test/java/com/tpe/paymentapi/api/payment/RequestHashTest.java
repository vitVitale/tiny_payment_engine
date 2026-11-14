package com.tpe.paymentapi.api.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentapi.application.validation.PaymentInstruction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/** specs/10-payment-api.md §5.1: SHA-256 of method + path + the body re-serialised with sorted keys. */
class RequestHashTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final RequestHash HASH = new RequestHash(JSON);

    @Test
    void theHashIsOfMethodPathAndTheCanonicalBody() throws Exception {
        var instruction = new PaymentInstruction("E2E", null, null,
                new PaymentInstruction.Amount("1.00", "EUR"), null, "VOP-1");

        String canonical = "{\"amount\":{\"currency\":\"EUR\",\"value\":\"1.00\"},\"creditor\":null,\"debtor\":null,"
                + "\"endToEndId\":\"E2E\",\"remittanceInformation\":null,\"vopReference\":\"VOP-1\"}";
        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(("POST /v1/payments\n" + canonical).getBytes(StandardCharsets.UTF_8)));
        assertThat(HASH.of("POST", "/v1/payments", instruction)).isEqualTo(expected);
    }

    @Test
    void propertyOrderDoesNotChangeTheHash() {
        var first = JSON.readTree("{\"b\":{\"y\":1,\"x\":[{\"q\":1,\"p\":2}]},\"a\":\"1\"}");
        var second = JSON.readTree("{ \"a\": \"1\", \"b\": { \"x\": [ { \"p\": 2, \"q\": 1 } ], \"y\": 1 } }");

        assertThat(HASH.of("POST", "/v1/payments", first)).isEqualTo(HASH.of("POST", "/v1/payments", second));
    }

    @Test
    void anotherValueMethodOrPathIsAnotherHash() {
        var body = JSON.readTree("{\"a\":\"1\"}");
        String hash = HASH.of("POST", "/v1/payments", body);

        assertThat(HASH.of("POST", "/v1/payments", JSON.readTree("{\"a\":\"2\"}"))).isNotEqualTo(hash);
        assertThat(HASH.of("PUT", "/v1/payments", body)).isNotEqualTo(hash);
        assertThat(HASH.of("POST", "/v1/other", body)).isNotEqualTo(hash);
    }

    @Test
    void anXmlBodyIsHashedInItsCanonicalFormWithTheVopReference() throws Exception {
        String xml = "<?xml version=\"1.0\"?><a  b='1'><!-- note --><c/></a>";

        String expected = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest("POST /v1/payments\n<a b=\"1\"><c></c></a>\nVoP-Reference: VOP-1"
                        .getBytes(StandardCharsets.UTF_8)));
        assertThat(HASH.ofXml("POST", "/v1/payments", xml, "VOP-1")).isEqualTo(expected);
    }

    @Test
    void xmlThatDiffersOnlyInCommentsIsTheSameRequestButAnotherReferenceIsNot() {
        String hash = HASH.ofXml("POST", "/v1/payments", "<a><c/></a>", "VOP-1");

        assertThat(HASH.ofXml("POST", "/v1/payments", "<a><!-- retry --><c/></a>", "VOP-1")).isEqualTo(hash);
        assertThat(HASH.ofXml("POST", "/v1/payments", "<a><c/></a>", "VOP-2")).isNotEqualTo(hash);
        assertThat(HASH.ofXml("POST", "/v1/payments", "<a><c/></a>", null)).isNotEqualTo(hash);
    }
}
