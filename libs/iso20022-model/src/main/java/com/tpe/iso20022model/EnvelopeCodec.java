package com.tpe.iso20022model;

import com.tpe.iso20022model.XsdValidator.Violation;
import com.tpe.iso20022model.head001v02.BusinessApplicationHeaderV02;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;
import javax.xml.validation.Schema;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Splits and joins the simulated CSM transport envelope {@code urn:tpe:csm:envelope:1}
 * (specs/01-shared-libraries.md §2.2): one {@code head.001.001.02} {@code AppHdr} and one ISO 20022 {@code Document}
 * of a {@link CsmMessageType}. Both directions validate both parts with {@link XsdValidator}. Thread-safe.
 *
 * <p>Like {@link XsdValidator}, violation messages may quote input: log the code and position, not the message.
 */
public final class EnvelopeCodec {

    public static final String NAMESPACE = "urn:tpe:csm:envelope:1";
    /** Classpath location of the envelope XSD. */
    static final String SCHEMA = "/envelope/csm-envelope.1.xsd";
    /** Code of an envelope part whose element is not {@code AppHdr} / {@code Document}. */
    public static final String UNEXPECTED_ELEMENT = "UNEXPECTED_ELEMENT";

    private static final String ENVELOPE_ELEMENT = "Envelope";
    private static final String HEADER_NAMESPACE = XsdValidator.NAMESPACE_PREFIX + "head.001.001.02";
    private static final String HEADER_ELEMENT = "AppHdr";
    private static final String DOCUMENT_ELEMENT = "Document";
    private static final Pattern XML_DECLARATION = Pattern.compile("^\\s*<\\?xml[^>]*\\?>\\s*");

    /** The part of an envelope that failed. */
    public enum Part { ENVELOPE, HEADER, DOCUMENT }

    /** The outcome of {@link #split(String)}. */
    public sealed interface SplitResult permits Accepted, Rejected {}

    /** Both parts are valid. */
    public record Accepted(CsmEnvelope envelope) implements SplitResult {}

    /**
     * {@code part} is invalid. Positions of {@link Part#ENVELOPE} violations, of {@link #UNEXPECTED_ELEMENT} and of
     * {@link XsdValidator#UNSUPPORTED_NAMESPACE} are in the envelope; positions of schema violations of the header or
     * document are within that part.
     */
    public record Rejected(Part part, List<Violation> violations) implements SplitResult {

        public Rejected {
            violations = List.copyOf(violations);
        }
    }

    /** A part handed to {@link #join(CsmEnvelope)} is not schema-valid. The message quotes violation codes only. */
    public static final class InvalidEnvelopeException extends IllegalArgumentException {

        private final Part part;
        private final transient List<Violation> violations;

        InvalidEnvelopeException(Part part, List<Violation> violations) {
            super("invalid " + part + ": " + violations.stream().map(Violation::code).distinct().toList());
            this.part = part;
            this.violations = List.copyOf(violations);
        }

        public Part part() {
            return part;
        }

        public List<Violation> violations() {
            return violations;
        }
    }

    private final Schema envelopeSchema = XsdValidator.compile(SecureXml.newSchemaFactory(), SCHEMA);
    private final XsdValidator validator;
    private final IsoCodec codec;

    public EnvelopeCodec() {
        this(new XsdValidator(), new IsoCodec());
    }

    public EnvelopeCodec(XsdValidator validator, IsoCodec codec) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    /**
     * The header and document of {@code xml}, or the first part that is invalid: envelope, header, document. An
     * envelope that lacks only its header or only its document is rejected as that part.
     */
    public SplitResult split(String xml) {
        Objects.requireNonNull(xml, "xml");
        List<Violation> envelopeViolations = XsdValidator.validateAgainst(envelopeSchema, xml);
        if (!envelopeViolations.isEmpty()) {
            return new Rejected(failedPart(xml, envelopeViolations), envelopeViolations);
        }
        List<RawPart> parts = RawPart.split(xml); // exactly two: the envelope schema says so
        RawPart header = parts.get(0);
        RawPart document = parts.get(1);
        if (!HEADER_ELEMENT.equals(header.localName())) {
            return new Rejected(Part.ENVELOPE, List.of(header.unexpected(HEADER_ELEMENT)));
        }
        if (!DOCUMENT_ELEMENT.equals(document.localName())) {
            return new Rejected(Part.ENVELOPE, List.of(document.unexpected(DOCUMENT_ELEMENT)));
        }
        List<Violation> headerViolations = validator.validate(header.xml());
        if (!headerViolations.isEmpty()) {
            return new Rejected(Part.HEADER, headerViolations);
        }
        var type = CsmMessageType.byNamespace(document.namespace());
        if (type.isEmpty()) {
            return new Rejected(Part.DOCUMENT, List.of(new Violation(document.line(), document.column(),
                    XsdValidator.UNSUPPORTED_NAMESPACE,
                    "unsupported document namespace '" + document.namespace() + "' in a CSM envelope")));
        }
        List<Violation> documentViolations = validator.validate(document.xml());
        if (!documentViolations.isEmpty()) {
            return new Rejected(Part.DOCUMENT, documentViolations);
        }
        return new Accepted(new CsmEnvelope(
                codec.unmarshal(header.xml(), BusinessApplicationHeaderV02.class),
                codec.unmarshal(document.xml(), type.get().documentType())));
    }

    /** The part a well-formed envelope lacks when it holds only the other one; otherwise the envelope itself. */
    private static Part failedPart(String xml, List<Violation> envelopeViolations) {
        boolean wellFormed = envelopeViolations.stream().map(Violation::code).noneMatch(code ->
                code.equals(XsdValidator.NOT_WELL_FORMED) || code.equals(XsdValidator.DOCTYPE_DISALLOWED));
        if (!wellFormed) {
            return Part.ENVELOPE;
        }
        List<RawPart> parts = RawPart.split(xml);
        if (parts.size() != 1) {
            return Part.ENVELOPE;
        }
        RawPart only = parts.get(0);
        if (HEADER_NAMESPACE.equals(only.namespace()) && HEADER_ELEMENT.equals(only.localName())) {
            return Part.DOCUMENT;
        }
        return DOCUMENT_ELEMENT.equals(only.localName()) ? Part.HEADER : Part.ENVELOPE;
    }

    /** The envelope XML of {@code envelope}; throws {@link InvalidEnvelopeException} if a part is not valid. */
    public String join(CsmEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        String header = marshalValid(Part.HEADER, envelope.header());
        String document = marshalValid(Part.DOCUMENT, envelope.document());
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Envelope xmlns=\"" + NAMESPACE + "\">" + header + document + "</Envelope>";
    }

    private String marshalValid(Part part, Object value) {
        String xml = codec.marshal(value);
        List<Violation> violations = validator.validate(xml);
        if (!violations.isEmpty()) {
            throw new InvalidEnvelopeException(part, violations);
        }
        return XML_DECLARATION.matcher(xml).replaceFirst("");
    }

    /** A child element of the envelope, written out as a standalone document with the namespaces in scope. */
    private record RawPart(String namespace, String localName, int line, int column, String xml) {

        Violation unexpected(String expected) {
            return new Violation(line, column, UNEXPECTED_ELEMENT,
                    "expected " + expected + " but found {" + namespace + "}" + localName);
        }

        /** The child elements of a well-formed envelope in document order; none if the root is no envelope. */
        static List<RawPart> split(String xml) {
            var splitter = new Splitter();
            XMLReader reader = SecureXml.newXmlReader();
            reader.setContentHandler(splitter);
            try {
                reader.parse(new InputSource(new StringReader(xml)));
            } catch (SAXException e) {
                // the cause may quote the input; it is kept for DEBUG only (specs/26-observability.md §4.4)
                throw new IllegalStateException("a well-formed envelope did not parse", e);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return splitter.isEnvelope ? splitter.parts : List.of();
        }
    }

    /** Copies the SAX events of each envelope child (depth 2) into a serializer of its own. */
    private static final class Splitter extends DefaultHandler {

        private final List<RawPart> parts = new ArrayList<>();
        /** Prefix mappings declared on the envelope element, inherited by its parts. */
        private final Map<String, String> envelopeMappings = new LinkedHashMap<>();
        /** Prefix mappings of the next element's start tag. */
        private final Map<String, String> pending = new LinkedHashMap<>();
        private Locator locator;
        private boolean isEnvelope;
        private int depth;
        private TransformerHandler out;
        private StringWriter buffer;
        private String namespace;
        private String localName;
        private int line;
        private int column;

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
            pending.put(prefix, uri);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes)
                throws SAXException {
            depth++;
            if (depth == 1) {
                isEnvelope = NAMESPACE.equals(uri) && ENVELOPE_ELEMENT.equals(localName);
                envelopeMappings.putAll(pending);
            } else if (depth == 2) {
                startPart(uri, localName);
                Map<String, String> inScope = new LinkedHashMap<>(envelopeMappings);
                inScope.putAll(pending); // the part's own declarations win
                for (var mapping : inScope.entrySet()) {
                    out.startPrefixMapping(mapping.getKey(), mapping.getValue());
                }
            } else {
                for (var mapping : pending.entrySet()) {
                    out.startPrefixMapping(mapping.getKey(), mapping.getValue());
                }
            }
            pending.clear();
            if (depth >= 2) {
                out.startElement(uri, localName, qName, attributes);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) throws SAXException {
            if (depth >= 2) {
                out.endElement(uri, localName, qName);
            }
            if (depth == 2) {
                out.endDocument();
                parts.add(new RawPart(namespace, this.localName, line, column, buffer.toString()));
                out = null;
            }
            depth--;
        }

        @Override
        public void characters(char[] ch, int start, int length) throws SAXException {
            if (depth >= 2) {
                out.characters(ch, start, length);
            }
        }

        @Override
        public void ignorableWhitespace(char[] ch, int start, int length) throws SAXException {
            if (depth >= 2) {
                out.ignorableWhitespace(ch, start, length);
            }
        }

        @Override
        public void processingInstruction(String target, String data) throws SAXException {
            if (depth >= 2) {
                out.processingInstruction(target, data);
            }
        }

        private void startPart(String uri, String localName) throws SAXException {
            this.namespace = uri;
            this.localName = localName;
            this.line = locator.getLineNumber();
            this.column = locator.getColumnNumber();
            buffer = new StringWriter();
            out = SecureXml.newSerializer();
            out.getTransformer().setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            out.setResult(new StreamResult(buffer));
            out.startDocument();
        }
    }
}
