package com.tpe.iso20022model;

import static com.tpe.iso20022model.GoldenSamples.golden;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class CanonicalXmlTest {

    @Test
    void commentsTheDeclarationAndAttributeOrderAreNotPartOfTheCanonicalForm() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <!-- a client's note -->
                <a xmlns="urn:x" z='1' b="2"><b><!-- inside -->t&amp;</b><c/></a>""";

        assertThat(CanonicalXml.of(xml)).isEqualTo("<a xmlns=\"urn:x\" b=\"2\" z=\"1\"><b>t&amp;</b><c></c></a>");
    }

    @Test
    void documentsThatDifferOnlyInCommentsAreCanonicallyEqual() {
        String document = golden("pain.001.001.09.xml");
        String commented = document.replace("<GrpHdr>", "<GrpHdr><!-- retry -->");

        assertThat(CanonicalXml.of(commented)).isEqualTo(CanonicalXml.of(document));
        assertThat(CanonicalXml.of(document.replace("123.45</InstdAmt>", "123.46</InstdAmt>")))
                .isNotEqualTo(CanonicalXml.of(document));
    }

    @Test
    void aDoctypeIsRefusedWithoutResolvingEntities() {
        String xml = "<!DOCTYPE a [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><a>&e;</a>";

        assertThatIllegalArgumentException().isThrownBy(() -> CanonicalXml.of(xml));
    }
}
