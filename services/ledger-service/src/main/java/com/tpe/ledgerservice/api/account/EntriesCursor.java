package com.tpe.ledgerservice.api.account;

import com.tpe.ledgerservice.api.FieldError;
import com.tpe.ledgerservice.api.ValidationFailedException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

/**
 * The opaque {@code cursor} of the entries feed (specs/12-ledger-service.md §3.2): base64url of the last posting id of
 * the previous page. Clients pass {@code nextCursor} back as is.
 */
final class EntriesCursor {

    private static final String PREFIX = "p:";

    private EntriesCursor() {}

    static String encode(long postingId) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((PREFIX + postingId).getBytes(StandardCharsets.US_ASCII));
    }

    /** @throws ValidationFailedException a cursor this service did not issue */
    static Long decode(String cursor) {
        if (cursor == null) {
            return null;
        }
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.US_ASCII);
            if (decoded.startsWith(PREFIX)) {
                long postingId = Long.parseLong(decoded.substring(PREFIX.length()));
                if (postingId > 0) {
                    return postingId;
                }
            }
        } catch (IllegalArgumentException e) {
            // not base64url or not a number: invalid like any other cursor we did not issue
        }
        throw new ValidationFailedException(List.of(new FieldError("cursor", "INVALID_CURSOR")));
    }
}
