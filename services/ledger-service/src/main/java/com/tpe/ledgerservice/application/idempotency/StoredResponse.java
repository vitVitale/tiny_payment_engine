package com.tpe.ledgerservice.application.idempotency;

/** An HTTP response as the idempotency guard stores and replays it: status and JSON body. */
public record StoredResponse(int status, String body) {

    /** Only deterministic outcomes are stored: {@code 2xx} and {@code 422} (specs/12-ledger-service.md §6.1). */
    boolean storable() {
        return isSuccess() || status == 422;
    }

    public boolean isSuccess() {
        return status >= 200 && status < 300;
    }
}
