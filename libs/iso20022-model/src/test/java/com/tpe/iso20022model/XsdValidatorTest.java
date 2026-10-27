package com.tpe.iso20022model;

import static com.tpe.iso20022model.GoldenSamples.golden;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.tpe.iso20022model.XsdValidator.Violation;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class XsdValidatorTest {

    private final XsdValidator validator = new XsdValidator();

    @ParameterizedTest
    @ValueSource(strings = {
        "head.001.001.02", "pain.001.001.09", "pain.002.001.10", "pacs.008.001.08",
        "pacs.002.001.10", "pacs.028.001.03", "camt.054.001.08",
    })
    void goldenSampleOfEverySupportedVersionIsValid(String version) {
        assertThat(validator.validate(golden(version + ".xml"))).isEmpty();
    }

    @Test
    void schemaInvalidSampleReportsViolationsWithLineAndColumn() {
        List<Violation> violations = validator.validate(golden("invalid/pacs.008.001.08-invalid.xml"));

        // line 7: NbOfTxs "one" does not match [0-9]{1,15}; line 12: CdtTrfTxInf is missing
        assertThat(violations).extracting(Violation::line).contains(7, 12);
        assertThat(violations).extracting(Violation::code).contains("cvc-pattern-valid", "cvc-complex-type.2.4.b");
        assertThat(violations).allSatisfy(violation -> {
            assertThat(violation.line()).isPositive();
            assertThat(violation.column()).isPositive();
            assertThat(violation.message()).isNotBlank();
        });
    }

    @Test
    void documentOfAnUnsupportedNamespaceIsAViolation() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.02"><FIToFICstmrCdtTrf/></Document>
                """;

        assertThat(validator.validate(xml)).singleElement().satisfies(violation -> {
            assertThat(violation.line()).isEqualTo(2);
            assertThat(violation.code()).isEqualTo(XsdValidator.UNSUPPORTED_NAMESPACE);
            assertThat(violation.message()).contains("pacs.008.001.02");
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08\">", "", "no xml"})
    void notWellFormedXmlIsAViolationAtAPosition(String xml) {
        assertThat(validator.validate(xml)).singleElement().satisfies(violation -> {
            assertThat(violation.code()).isEqualTo(XsdValidator.NOT_WELL_FORMED);
            assertThat(violation.line()).isPositive();
            assertThat(violation.column()).isPositive();
        });
    }

    @Test
    void ISO_AC_02_doctypeWithAnExternalFileEntityIsRejectedWithoutReadingTheFile(@TempDir Path dir)
            throws IOException {
        Path secret = Files.writeString(dir.resolve("secret.txt"), "TOP-SECRET-CANARY");
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!DOCTYPE Document [<!ENTITY xxe SYSTEM "%s">]>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.008.001.08">
                  <FIToFICstmrCdtTrf><GrpHdr><MsgId>&xxe;</MsgId></GrpHdr></FIToFICstmrCdtTrf>
                </Document>
                """.formatted(secret.toUri());

        List<Violation> violations = validator.validate(xml);

        assertThat(violations).singleElement().satisfies(violation -> {
            assertThat(violation.line()).isEqualTo(2);
            assertThat(violation.code()).isEqualTo(XsdValidator.DOCTYPE_DISALLOWED);
            assertThat(violation.message()).doesNotContain("TOP-SECRET-CANARY");
        });
    }

    @Test
    void ISO_AC_02_doctypeWithAnExternalUrlEntityIsRejectedWithoutFetchingIt() throws IOException {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        try {
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/xxe";
            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE Document SYSTEM "%1$s" [<!ENTITY %% remote SYSTEM "%1$s"> %%remote;]>
                    <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10"/>
                    """.formatted(url);

            assertThat(validator.validate(xml)).singleElement()
                    .satisfies(violation -> assertThat(violation.code()).isEqualTo(XsdValidator.DOCTYPE_DISALLOWED));
            assertThat(requests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void ISO_AC_02_internalEntityExpansionIsRejectedToo() {
        String billionLaughs = """
                <?xml version="1.0"?>
                <!DOCTYPE Document [<!ENTITY a "aaaaaaaaaa"><!ENTITY b "&a;&a;&a;&a;&a;&a;&a;&a;&a;&a;">]>
                <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10">&b;</Document>
                """;

        assertThat(validator.validate(billionLaughs)).singleElement()
                .satisfies(violation -> assertThat(violation.code()).isEqualTo(XsdValidator.DOCTYPE_DISALLOWED));
    }

    @Test
    void validatorIsReusableAcrossDocuments() {
        assertThat(validator.validate(golden("pacs.008.001.08.xml"))).isEmpty();
        assertThat(validator.validate(golden("invalid/pacs.008.001.08-invalid.xml"))).isNotEmpty();
        assertThat(validator.validate(golden("pacs.002.001.10.xml"))).isEmpty();
    }
}
