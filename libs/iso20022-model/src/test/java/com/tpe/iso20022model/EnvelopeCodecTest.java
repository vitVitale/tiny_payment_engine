package com.tpe.iso20022model;

import static com.tpe.iso20022model.GoldenSamples.golden;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.iso20022model.EnvelopeCodec.Accepted;
import com.tpe.iso20022model.EnvelopeCodec.InvalidEnvelopeException;
import com.tpe.iso20022model.EnvelopeCodec.Part;
import com.tpe.iso20022model.EnvelopeCodec.Rejected;
import com.tpe.iso20022model.EnvelopeCodec.SplitResult;
import com.tpe.iso20022model.XsdValidator.Violation;
import com.tpe.iso20022model.head001v02.BusinessApplicationHeaderV02;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class EnvelopeCodecTest {

    private static final String HEAD_NS = XsdValidator.NAMESPACE_PREFIX + "head.001.001.02";
    private static final String PACS_028 = "envelope/pacs.028.001.03.xml";

    private final EnvelopeCodec envelopes = new EnvelopeCodec();
    private final IsoCodec iso = new IsoCodec();

    static Stream<Arguments> messageTypes() {
        return Stream.of(
                Arguments.of("pacs.008.001.08", CsmMessageType.PACS_008),
                Arguments.of("pacs.002.001.10", CsmMessageType.PACS_002),
                Arguments.of("pacs.028.001.03", CsmMessageType.PACS_028));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("messageTypes")
    void goldenEnvelopeSplitsIntoItsHeaderAndTheGoldenDocument(String version, CsmMessageType type) {
        CsmEnvelope envelope = accepted(envelopes.split(golden("envelope/" + version + ".xml")));

        assertThat(envelope.messageType()).isEqualTo(type);
        assertThat(envelope.document()).isEqualTo(iso.unmarshal(golden(version + ".xml"), type.documentType()));
        assertThat(envelope.header().getMsgDefIdr()).isEqualTo(version);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("messageTypes")
    void ISO_AC_03_joinThenSplitReturnsAnEqualHeaderAndDocument(String version, CsmMessageType type) {
        CsmEnvelope original = accepted(envelopes.split(golden("envelope/" + version + ".xml")));

        String joined = envelopes.join(original);
        CsmEnvelope roundTripped = accepted(envelopes.split(joined));

        assertThat(joined).contains(EnvelopeCodec.NAMESPACE);
        assertThat(roundTripped.header()).isEqualTo(original.header()).isNotSameAs(original.header());
        assertThat(roundTripped.document()).isEqualTo(original.document()).isNotSameAs(original.document());
        assertThat(roundTripped).isEqualTo(original);
    }

    @Test
    void envelopeSchemaExistsOnTheClasspath() {
        assertThat(EnvelopeCodec.class.getResource(EnvelopeCodec.SCHEMA)).isNotNull();
    }

    @Test
    void partsWrittenWithPrefixesDeclaredOnTheEnvelopeSplit() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <e:Envelope xmlns:e="urn:tpe:csm:envelope:1" xmlns:h="%s"
                            xmlns:d="urn:iso:std:iso:20022:tech:xsd:pacs.028.001.03">
                  <h:AppHdr>
                    <h:Fr><h:FIId><h:FinInstnId><h:BICFI>TPEBDEFFXXX</h:BICFI></h:FinInstnId></h:FIId></h:Fr>
                    <h:To><h:FIId><h:FinInstnId><h:BICFI>PEERDEFFXXX</h:BICFI></h:FinInstnId></h:FIId></h:To>
                    <h:BizMsgIdr>M1</h:BizMsgIdr><h:MsgDefIdr>pacs.028.001.03</h:MsgDefIdr>
                    <h:CreDt>2026-10-21T09:15:52Z</h:CreDt>
                  </h:AppHdr>
                  <d:Document><d:FIToFIPmtStsReq>
                    <d:GrpHdr><d:MsgId>M1</d:MsgId><d:CreDtTm>2026-10-21T09:15:52Z</d:CreDtTm></d:GrpHdr>
                  </d:FIToFIPmtStsReq></d:Document>
                </e:Envelope>
                """.formatted(HEAD_NS);

        CsmEnvelope envelope = accepted(envelopes.split(xml));

        assertThat(envelope.messageType()).isEqualTo(CsmMessageType.PACS_028);
        assertThat(envelope.header().getBizMsgIdr()).isEqualTo("M1");
    }

    @Test
    void invalidHeaderIsRejectedAsTheHeaderPart() {
        String xml = golden("envelope/pacs.008.001.08.xml")
                .replaceFirst("<BICFI>TPEBDEFFXXX</BICFI>", "<BICFI>not a bic</BICFI>");

        Rejected rejected = rejected(envelopes.split(xml));

        assertThat(rejected.part()).isEqualTo(Part.HEADER);
        assertThat(rejected.violations()).extracting(Violation::code).contains("cvc-pattern-valid");
    }

    @Test
    void invalidHeaderIsReportedEvenWhenTheDocumentNamespaceIsUnsupported() {
        String xml = withDocument(golden("pain.001.001.09.xml"))
                .replaceFirst("<BICFI>TPEBDEFFXXX</BICFI>", "<BICFI>not a bic</BICFI>");

        assertThat(rejected(envelopes.split(xml)).part()).isEqualTo(Part.HEADER);
    }

    @Test
    void invalidDocumentIsRejectedAsTheDocumentPart() {
        String xml = golden("envelope/pacs.008.001.08.xml")
                .replace("<NbOfTxs>1</NbOfTxs>", "<NbOfTxs>one</NbOfTxs>");

        Rejected rejected = rejected(envelopes.split(xml));

        assertThat(rejected.part()).isEqualTo(Part.DOCUMENT);
        assertThat(rejected.violations()).extracting(Violation::code).contains("cvc-pattern-valid");
    }

    @Test
    void envelopeWithoutDocumentIsRejectedAsTheDocumentPart() {
        Rejected rejected = rejected(envelopes.split(withDocument("")));

        assertThat(rejected.part()).isEqualTo(Part.DOCUMENT);
        assertThat(rejected.violations()).extracting(Violation::code).containsExactly("cvc-complex-type.2.4.b");
    }

    @Test
    void envelopeWithoutHeaderIsRejectedAsTheHeaderPart() {
        String xml = "<Envelope xmlns=\"urn:tpe:csm:envelope:1\">" + fragment(golden("pacs.028.001.03.xml"))
                + "</Envelope>";

        Rejected rejected = rejected(envelopes.split(xml));

        assertThat(rejected.part()).isEqualTo(Part.HEADER);
        assertThat(rejected.violations()).isNotEmpty();
    }

    @Test
    void emptyEnvelopeIsRejectedAsTheEnvelopePart() {
        assertThat(rejected(envelopes.split("<Envelope xmlns=\"urn:tpe:csm:envelope:1\"/>")).part())
                .isEqualTo(Part.ENVELOPE);
    }

    @Test
    void envelopeWithAThirdPartIsRejected() {
        String xml = golden(PACS_028).replace("</Envelope>", "<Extra xmlns=\"urn:x\"/></Envelope>");

        assertThat(rejected(envelopes.split(xml)).part()).isEqualTo(Part.ENVELOPE);
    }

    @Test
    void headerElementOtherThanAppHdrIsRejectedAtItsPosition() {
        String xml = """
                <Envelope xmlns="urn:tpe:csm:envelope:1">
                  <Fr xmlns="%s"/>
                  <Document xmlns="urn:iso:std:iso:20022:tech:xsd:pacs.028.001.03"/>
                </Envelope>
                """.formatted(HEAD_NS);

        Rejected rejected = rejected(envelopes.split(xml));

        assertThat(rejected.part()).isEqualTo(Part.ENVELOPE);
        assertThat(rejected.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.code()).isEqualTo(EnvelopeCodec.UNEXPECTED_ELEMENT);
            assertThat(violation.line()).isEqualTo(2);
        });
    }

    @Test
    void documentElementOtherThanDocumentIsRejected() {
        String xml = withDocument("<Doc xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.028.001.03\"/>");

        Rejected rejected = rejected(envelopes.split(xml));

        assertThat(rejected.part()).isEqualTo(Part.ENVELOPE);
        assertThat(rejected.violations()).extracting(Violation::code)
                .containsExactly(EnvelopeCodec.UNEXPECTED_ELEMENT);
    }

    @Test
    void ISO_AC_02_envelopeWithADoctypeIsRejectedWithoutResolvingIt() {
        String xml = golden("envelope/pacs.008.001.08.xml").replace(
                "<Envelope", "<!DOCTYPE Envelope [<!ENTITY a \"expanded\">]>\n<Envelope");

        Rejected rejected = rejected(envelopes.split(xml));

        assertThat(rejected.part()).isEqualTo(Part.ENVELOPE);
        assertThat(rejected.violations()).singleElement()
                .satisfies(violation -> assertThat(violation.code()).isEqualTo(XsdValidator.DOCTYPE_DISALLOWED));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "no xml", "<Envelope xmlns=\"urn:tpe:csm:envelope:1\">"})
    void notWellFormedInputIsRejectedAsTheEnvelopePart(String xml) {
        Rejected rejected = rejected(envelopes.split(xml));

        assertThat(rejected.part()).isEqualTo(Part.ENVELOPE);
        assertThat(rejected.violations()).extracting(Violation::code).containsExactly(XsdValidator.NOT_WELL_FORMED);
    }

    @Test
    void bareDocumentWithoutEnvelopeIsRejected() {
        assertThat(rejected(envelopes.split(golden("pacs.028.001.03.xml"))).part()).isEqualTo(Part.ENVELOPE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"pain.001.001.09", "camt.054.001.08"})
    void documentOfAMessageTheCsmDoesNotCarryIsAnUnsupportedNamespace(String version) {
        Rejected rejected = rejected(envelopes.split(withDocument(golden(version + ".xml"))));

        assertThat(rejected.part()).isEqualTo(Part.DOCUMENT);
        assertThat(rejected.violations()).singleElement().satisfies(violation -> {
            assertThat(violation.code()).isEqualTo(XsdValidator.UNSUPPORTED_NAMESPACE);
            assertThat(violation.message()).contains(version);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:pacs.008.001.02\"/>",
        "<Document/>",
        "<Document xmlns=\"urn:tpe:csm:envelope:1\"/>",
        "<Document xmlns=\"urn:iso:std:iso:20022:tech:xsd:head.001.001.02\"/>",
    })
    void documentOfAnUnknownOrMissingNamespaceIsAnUnsupportedNamespace(String document) {
        Rejected rejected = rejected(envelopes.split(withDocument(document)));

        assertThat(rejected.part()).isEqualTo(Part.DOCUMENT);
        assertThat(rejected.violations()).extracting(Violation::code)
                .containsExactly(XsdValidator.UNSUPPORTED_NAMESPACE);
    }

    @Test
    void joinRejectsAnInvalidHeader() {
        CsmEnvelope valid = accepted(envelopes.split(golden("envelope/pacs.008.001.08.xml")));
        var header = iso.unmarshal(iso.marshal(valid.header()), BusinessApplicationHeaderV02.class);
        header.setBizMsgIdr(null);

        assertThatThrownBy(() -> envelopes.join(new CsmEnvelope(header, valid.document())))
                .isInstanceOfSatisfying(InvalidEnvelopeException.class, e -> {
                    assertThat(e.part()).isEqualTo(Part.HEADER);
                    assertThat(e.violations()).isNotEmpty();
                });
    }

    @Test
    void joinRejectsAnInvalidDocumentWithoutQuotingIt() {
        CsmEnvelope valid = accepted(envelopes.split(golden("envelope/pacs.008.001.08.xml")));
        var document = (com.tpe.iso20022model.pacs008v08.Document) valid.document();
        document.getFIToFICstmrCdtTrf().getGrpHdr().setNbOfTxs("one");

        assertThatThrownBy(() -> envelopes.join(valid))
                .isInstanceOfSatisfying(InvalidEnvelopeException.class, e -> {
                    assertThat(e.part()).isEqualTo(Part.DOCUMENT);
                    assertThat(e.getMessage()).contains("cvc-pattern-valid").doesNotContain("one");
                });
    }

    @Test
    void envelopeCarriesOnlyCsmMessages() {
        CsmEnvelope valid = accepted(envelopes.split(golden("envelope/pacs.008.001.08.xml")));
        Object pain001 = iso.unmarshal(golden("pain.001.001.09.xml"), com.tpe.iso20022model.pain001v09.Document.class);

        assertThatThrownBy(() -> new CsmEnvelope(valid.header(), pain001)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void messageTypeIsSelectedByNamespace() {
        assertThat(CsmMessageType.byNamespace("urn:iso:std:iso:20022:tech:xsd:pacs.002.001.10"))
                .contains(CsmMessageType.PACS_002);
        assertThat(CsmMessageType.byNamespace("urn:iso:std:iso:20022:tech:xsd:pain.001.001.09")).isEmpty();
    }

    /** The golden pacs.028 envelope with its document replaced by {@code document} (a sample or a fragment). */
    private static String withDocument(String document) {
        String envelope = golden(PACS_028);
        return envelope.substring(0, envelope.indexOf("<Document")) + fragment(document) + "</Envelope>";
    }

    private static String fragment(String xml) {
        return xml.replaceFirst("<\\?xml[^>]*\\?>", "");
    }

    private static CsmEnvelope accepted(SplitResult result) {
        assertThat(result).isInstanceOf(Accepted.class);
        return ((Accepted) result).envelope();
    }

    private static Rejected rejected(SplitResult result) {
        assertThat(result).isInstanceOf(Rejected.class);
        return (Rejected) result;
    }
}
