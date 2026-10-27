package com.tpe.iso20022model;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.annotation.XmlElementDecl;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import javax.xml.namespace.QName;
import javax.xml.transform.sax.SAXSource;
import org.xml.sax.InputSource;

/**
 * Turns generated ISO 20022 document objects into XML and back (specs/01-shared-libraries.md §2.2). JAXB contexts
 * are created once per message version and cached; marshallers and unmarshallers are created per call, so the
 * codec is thread-safe. Parsing rejects DOCTYPE declarations; validate untrusted XML with {@link XsdValidator}
 * first.
 */
public final class IsoCodec {

    private final Map<Package, JAXBContext> contexts = new ConcurrentHashMap<>();
    private final Map<Class<?>, QName> rootElements = new ConcurrentHashMap<>();

    public String marshal(Object document) {
        Objects.requireNonNull(document, "document");
        Class<?> type = document.getClass();
        @SuppressWarnings({"unchecked", "rawtypes"})
        JAXBElement<?> root = new JAXBElement(rootElement(type), type, document);
        try {
            Marshaller marshaller = context(type).createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_ENCODING, "UTF-8");
            StringWriter xml = new StringWriter();
            marshaller.marshal(root, xml);
            return xml.toString();
        } catch (JAXBException e) {
            throw new IllegalStateException("cannot marshal " + type.getName(), e);
        }
    }

    /** Reads a document of {@code type}; the root element must be the one of that type. */
    public <T> T unmarshal(String xml, Class<T> type) {
        Objects.requireNonNull(xml, "xml");
        QName expected = rootElement(type);
        JAXBElement<T> root;
        try {
            var source = new SAXSource(SecureXml.newXmlReader(), new InputSource(new StringReader(xml)));
            root = context(type).createUnmarshaller().unmarshal(source, type);
        } catch (JAXBException e) {
            // the cause may quote the input; it is kept for DEBUG only (specs/26-observability.md §4.4)
            throw new IllegalArgumentException("not a well-formed " + expected.getNamespaceURI() + " document", e);
        }
        if (!expected.equals(root.getName())) {
            throw new IllegalArgumentException("root element " + root.getName() + " is not " + expected);
        }
        return root.getValue();
    }

    private JAXBContext context(Class<?> type) {
        return contexts.computeIfAbsent(type.getPackage(), pkg -> {
            try {
                return JAXBContext.newInstance(pkg.getName(), type.getClassLoader());
            } catch (JAXBException e) {
                throw new IllegalStateException("cannot create the JAXB context of " + pkg.getName(), e);
            }
        });
    }

    /** The global element of {@code type}, declared on its package's generated ObjectFactory. */
    private QName rootElement(Class<?> type) {
        return rootElements.computeIfAbsent(type, IsoCodec::findRootElement);
    }

    private static QName findRootElement(Class<?> type) {
        Class<?> objectFactory;
        try {
            objectFactory = Class.forName(type.getPackageName() + ".ObjectFactory", false, type.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalArgumentException(type.getName() + " is not a generated ISO 20022 type");
        }
        return Arrays.stream(objectFactory.getMethods())
                .filter(method -> method.isAnnotationPresent(XmlElementDecl.class))
                .filter(method -> method.getParameterCount() == 1 && method.getParameterTypes()[0] == type)
                .map(IsoCodec::elementName)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(type.getName() + " is not an ISO 20022 document"));
    }

    private static QName elementName(Method factoryMethod) {
        XmlElementDecl declaration = factoryMethod.getAnnotation(XmlElementDecl.class);
        return new QName(declaration.namespace(), declaration.name());
    }
}
