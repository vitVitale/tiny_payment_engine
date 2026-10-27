package com.tpe.iso20022model;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;

/**
 * Hardened XML parsing for untrusted input: a DOCTYPE is a fatal error, so no entity, external or internal, is ever
 * declared or resolved (XXE, entity expansion; specs/01-shared-libraries.md §2.2), and schemas and validators may
 * not fetch anything external.
 */
final class SecureXml {

    /** The parser feature that turns a DOCTYPE into a fatal error; its URI appears in that error's message. */
    static final String DISALLOW_DOCTYPE = "http://apache.org/xml/features/disallow-doctype-decl";

    private static final SAXParserFactory FACTORY = newFactory();
    private static final SAXTransformerFactory TRANSFORMER_FACTORY = newTransformerFactory();

    private SecureXml() {}

    /** A namespace-aware reader that rejects DOCTYPE declarations. Not thread-safe: one per parse. */
    static XMLReader newXmlReader() {
        try {
            synchronized (FACTORY) {
                return FACTORY.newSAXParser().getXMLReader();
            }
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("cannot create a secure XML parser", e);
        }
    }

    /** A schema factory for self-contained XSDs: no external DTD or schema access. */
    static SchemaFactory newSchemaFactory() {
        SchemaFactory factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
        try {
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (SAXException e) {
            throw new IllegalStateException("the JDK schema factory does not support secure processing", e);
        }
        return factory;
    }

    /** A validator of {@code schema} without external DTD or schema access. Not thread-safe: one per validation. */
    static Validator newValidator(Schema schema) {
        Validator validator = schema.newValidator();
        try {
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        } catch (SAXException e) {
            throw new IllegalStateException("the JDK validator does not support secure processing", e);
        }
        return validator;
    }

    /** A handler that writes the SAX events it receives as XML; it never parses or fetches anything. One per output. */
    static TransformerHandler newSerializer() {
        try {
            synchronized (TRANSFORMER_FACTORY) {
                return TRANSFORMER_FACTORY.newTransformerHandler();
            }
        } catch (TransformerConfigurationException e) {
            throw new IllegalStateException("cannot create an XML serializer", e);
        }
    }

    private static SAXTransformerFactory newTransformerFactory() {
        try {
            var factory = (SAXTransformerFactory) TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            return factory;
        } catch (TransformerConfigurationException | IllegalArgumentException e) {
            throw new IllegalStateException("the JDK transformer does not support secure processing", e);
        }
    }

    private static SAXParserFactory newFactory() {
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true);
            factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature(DISALLOW_DOCTYPE, true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            return factory;
        } catch (ParserConfigurationException | SAXException e) {
            throw new IllegalStateException("the JDK XML parser does not support secure processing", e);
        }
    }
}
