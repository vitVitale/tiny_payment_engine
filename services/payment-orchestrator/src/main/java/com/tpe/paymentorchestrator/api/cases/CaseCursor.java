package com.tpe.paymentorchestrator.api.cases;

import com.tpe.paymentorchestrator.api.FieldError;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * The opaque {@code cursor} of the case list (specs/11-payment-orchestrator.md §3.2): base64url of the last case id of
 * the previous page. Clients pass {@code nextCursor} back as is.
 */
final class CaseCursor {

    private static final String PREFIX = "c:";

    private CaseCursor() {}

    static String encode(UUID caseId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((PREFIX + caseId).getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * @param errors gets {@code cursor INVALID_CURSOR} for a cursor this service did not issue
     * @return {@code null} for no cursor or an invalid one
     */
    static UUID decode(String cursor, List<FieldError> errors) {
        if (cursor == null) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            if (decoded.startsWith(PREFIX)) {
                return UUID.fromString(decoded.substring(PREFIX.length()));
            }
        } catch (IllegalArgumentException e) {
            // not base64url or not a UUID: invalid like any other cursor we did not issue
        }
        errors.add(new FieldError("cursor", "INVALID_CURSOR"));
        return null;
    }
}
