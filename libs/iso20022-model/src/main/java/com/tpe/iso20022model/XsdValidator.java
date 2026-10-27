package com.tpe.iso20022model;

import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.xml.sax.Attributes;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Validates a raw ISO 20022 document against the XSD of its root element's namespace, before anything trusts it.
 * A DOCTYPE is rejected without resolving entities (ISO-AC-02). Thread-safe: schemas are compiled once, a validator
 * is created per call.
 *
 * <p>Violation messages may quote input values (names, IBANs): log the {@link Violation#code() code} and position,
 * not the message, at INFO and above (specs/26-observability.md §4.4).
 */
public final class XsdValidator {

    /** Code of a document whose root namespace is none of the supported message versions. */
    public static final String UNSUPPORTED_NAMESPACE = "UNSUPPORTED_NAMESPACE";
    /** Code of a document with a DOCTYPE declaration. */
    public static final String DOCTYPE_DISALLOWED = "DOCTYPE_DISALLOWED";
    /** Code of input that is not well-formed XML. */
    public static final String NOT_WELL_FORMED = "NOT_WELL_FORMED";

    /** The supported message versions of specs/01-shared-libraries.md §2.1. */
    static final List<String> VERSIONS = List.of(
            "head.001.001.02", "pain.001.001.09", "pain.002.001.10", "pacs.008.001.08",
            "pacs.002.001.10", "pacs.028.001.03", "camt.054.001.08");

    static final String NAMESPACE_PREFIX ="urn:iso:std:iso:20022:tech:xsd:";
    /** Schema validation messages start with their XML Schema rule key, e.g. {@code cvc-pattern-valid: ...}. */
    private static final Pattern RULE_KEY = Pattern.compile("^(cvc-[a-zA-Z0-9.-]+):");

    /**
     * A position in the document (1-based line and column), a code for logging, and a message that may quote input.
     * The code is {@link #UNSUPPORTED_NAMESPACE}, {@link #DOCTYPE_DISALLOWED}, {@link #NOT_WELL_FORMED}, or the
     * XML Schema rule key of a schema violation such as {@code cvc-pattern-valid}.
     */
    public record Violation(int line, int column, String code, String message) {}

    private final Map<String, Schema> schemasByNamespace;

    public XsdValidator() {
        SchemaFactory factory = SecureXml.newSchemaFactory();
        this.schemasByNamespace = VERSIONS.stream().collect(
                Collectors.toUnmodifiableMap(version -> NAMESPACE_PREFIX + version,
                        version -> compile(factory, "/xsd/" + version + ".xsd")));
    }

    /** Compiles the self-contained XSD at classpath {@code resource}. */
    static Schema compile(SchemaFactory factory, String resource) {
        URL xsd = Objects.requireNonNull(XsdValidator.class.getResource(resource), resource);
        try {
            return factory.newSchema(new StreamSource(xsd.openStream(), xsd.toExternalForm()));
        } catch (SAXException e) {
            throw new IllegalStateException("cannot compile " + resource, e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** All violations of {@code xml}, empty if it is a valid document of a supported version. */
    public List<Violation> validate(String xml) {
        Objects.requireNonNull(xml, "xml");
        RootElement root;
        try {
            root = RootElement.of(xml);
        } catch (SAXParseException e) {
            return List.of(violation(e));
        }
        Schema schema = schemasByNamespace.get(root.namespace());
        if (schema == null) {
            return List.of(new Violation(root.line(), root.column(), UNSUPPORTED_NAMESPACE,
                    "unsupported document namespace '" + root.namespace() + "'"));
        }
        return validateAgainst(schema, xml);
    }

    /** All violations of {@code xml} against {@code schema}, whatever its root namespace. */
    static List<Violation> validateAgainst(Schema schema, String xml) {
        List<Violation> violations = new ArrayList<>();
        Validator validator = SecureXml.newValidator(schema);
        validator.setErrorHandler(new Collector(violations));
        try {
            validator.validate(new SAXSource(SecureXml.newXmlReader(), new InputSource(new StringReader(xml))));
        } catch (SAXParseException e) {
            // fatal: already collected by the error handler
        } catch (SAXException e) {
            violations.add(new Violation(1, 1, NOT_WELL_FORMED, e.getMessage()));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return List.copyOf(violations);
    }

    private static Violation violation(SAXParseException e) {
        String message = String.valueOf(e.getMessage());
        Matcher rule = RULE_KEY.matcher(message);
        String code = rule.find() ? rule.group(1)
                : message.contains(SecureXml.DISALLOW_DOCTYPE) ? DOCTYPE_DISALLOWED
                : NOT_WELL_FORMED;
        // the parser reports -1 when it has no position (e.g. empty input); positions are 1-based
        return new Violation(Math.max(1, e.getLineNumber()), Math.max(1, e.getColumnNumber()), code, message);
    }

    private record Collector(List<Violation> violations) implements ErrorHandler {

        @Override
        public void warning(SAXParseException e) {}

        @Override
        public void error(SAXParseException e) {
            violations.add(violation(e));
        }

        @Override
        public void fatalError(SAXParseException e) throws SAXParseException {
            violations.add(violation(e));
            throw e;
        }
    }

    /** Namespace and position of the root element, read with the secure parser up to that element only. */
    private record RootElement(String namespace, int line, int column) {

        private static final String STOP = "root element found";

        static RootElement of(String xml) throws SAXParseException {
            var handler = new DefaultHandler() {
                private Locator locator;
                private RootElement root;

                @Override
                public void setDocumentLocator(Locator locator) {
                    this.locator = locator;
                }

                @Override
                public void startElement(String uri, String localName, String qName, Attributes attributes)
                        throws SAXException {
                    root = new RootElement(uri, locator.getLineNumber(), locator.getColumnNumber());
                    throw new SAXException(STOP); // nothing after the root element's start tag is needed
                }

                @Override
                public void fatalError(SAXParseException e) throws SAXParseException {
                    throw e;
                }
            };
            XMLReader reader = SecureXml.newXmlReader();
            reader.setContentHandler(handler);
            reader.setErrorHandler(handler);
            try {
                reader.parse(new InputSource(new StringReader(xml)));
            } catch (SAXParseException e) {
                throw e;
            } catch (SAXException e) {
                if (handler.root != null) {
                    return handler.root;
                }
                throw new SAXParseException(e.getMessage(), null);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            throw new SAXParseException("document has no root element", null);
        }
    }
}
