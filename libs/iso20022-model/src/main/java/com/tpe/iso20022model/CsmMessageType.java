package com.tpe.iso20022model;

import java.util.Arrays;
import java.util.Optional;

/**
 * The ISO 20022 messages a CSM envelope carries (specs/15-csm-gateway.md §3.1). The document's namespace selects the
 * type; later messages (pacs.004, camt.056, camt.029) are added with UC-11 / UC-12.
 */
public enum CsmMessageType {

    PACS_008("pacs.008.001.08", com.tpe.iso20022model.pacs008v08.Document.class),
    PACS_002("pacs.002.001.10", com.tpe.iso20022model.pacs002v10.Document.class),
    PACS_028("pacs.028.001.03", com.tpe.iso20022model.pacs028v03.Document.class);

    private final String version;
    private final Class<?> documentType;

    CsmMessageType(String version, Class<?> documentType) {
        this.version = version;
        this.documentType = documentType;
    }

    /** The message version, e.g. {@code pacs.008.001.08}, as in the header's {@code MsgDefIdr}. */
    public String version() {
        return version;
    }

    public String namespace() {
        return XsdValidator.NAMESPACE_PREFIX + version;
    }

    /** The generated JAXB class of the document. */
    public Class<?> documentType() {
        return documentType;
    }

    public static Optional<CsmMessageType> byNamespace(String namespace) {
        return Arrays.stream(values()).filter(type -> type.namespace().equals(namespace)).findFirst();
    }

    public static Optional<CsmMessageType> byDocumentType(Class<?> documentType) {
        return Arrays.stream(values()).filter(type -> type.documentType == documentType).findFirst();
    }
}
