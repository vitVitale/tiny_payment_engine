package com.tpe.paymentapi.application.idempotency;

import java.util.Objects;

/**
 * A request under an {@code Idempotency-Key} (specs/10-payment-api.md §5.1).
 *
 * @param clientId the token's {@code client_id}; keys are the client's own
 * @param key the {@code Idempotency-Key} header, already checked
 * @param requestHash hex SHA-256 of method, path and canonical body
 */
public record IdempotentRequest(String clientId, String key, String requestHash) {

    public IdempotentRequest {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(requestHash, "requestHash");
    }
}
