package com.tpe.iso20022model;

import com.tpe.iso20022model.head001v02.BusinessApplicationHeaderV02;
import java.util.Objects;

/**
 * The two parts of a CSM transport envelope (specs/01-shared-libraries.md §2.2): the {@code head.001} header and one
 * document of a {@link CsmMessageType}. Equal when both parts are equal.
 */
public record CsmEnvelope(BusinessApplicationHeaderV02 header, Object document) {

    public CsmEnvelope {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(document, "document");
        if (CsmMessageType.byDocumentType(document.getClass()).isEmpty()) {
            throw new IllegalArgumentException(document.getClass().getName() + " is not carried by a CSM envelope");
        }
    }

    public CsmMessageType messageType() {
        return CsmMessageType.byDocumentType(document.getClass()).orElseThrow();
    }
}
