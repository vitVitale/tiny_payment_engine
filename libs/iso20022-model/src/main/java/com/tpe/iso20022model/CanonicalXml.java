package com.tpe.iso20022model;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import javax.xml.crypto.OctetStreamData;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.TransformException;
import javax.xml.crypto.dsig.TransformService;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Canonical XML 1.0 without comments (W3C C14N, {@link CanonicalizationMethod#INCLUSIVE}) of a document: two documents
 * that differ only in the XML declaration, comments, attribute order, quoting or empty-element notation have the same
 * canonical form. The JDK's canonicalizer does the work; the input is first read by the secure parser, so a document
 * with a DOCTYPE is refused before the canonicalizer parses anything (specs/01-shared-libraries.md §2.2). The string is
 * handed over as UTF-8, the encoding of every ISO 20022 message we accept.
 */
public final class CanonicalXml {

    private CanonicalXml() {}

    /**
     * @throws IllegalArgumentException for input that is not well-formed or has a DOCTYPE; the cause may quote the
     *     input
     */
    public static String of(String xml) {
        Objects.requireNonNull(xml, "xml");
        requireSecure(xml);
        try {
            TransformService c14n = TransformService.getInstance(CanonicalizationMethod.INCLUSIVE, "DOM");
            c14n.init(null);
            var input = new OctetStreamData(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            var canonical = (OctetStreamData) c14n.transform(input, null);
            return new String(canonical.getOctetStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (NoSuchAlgorithmException | InvalidAlgorithmParameterException e) {
            throw new IllegalStateException("every JDK has Canonical XML 1.0", e);
        } catch (TransformException e) {
            throw new IllegalArgumentException("not a well-formed XML document", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Parses {@code xml} with the secure parser, which refuses a DOCTYPE, and reads nothing from it. */
    private static void requireSecure(String xml) {
        var reader = SecureXml.newXmlReader();
        reader.setErrorHandler(new DefaultHandler() {
            @Override
            public void fatalError(SAXParseException e) throws SAXParseException {
                throw e;
            }
        });
        try {
            reader.parse(new InputSource(new StringReader(xml)));
        } catch (SAXException e) {
            throw new IllegalArgumentException("not a well-formed XML document without a DOCTYPE", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
