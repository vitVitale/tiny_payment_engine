package com.tpe.paymentapi.application.rejection;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** {@code payment_rejections} (specs/10-payment-api.md §5.1): the {@code pain.002 RJCT FF01} documents of XML clients. */
public interface PaymentRejections {

    void store(UUID rejectionId, String clientId, String xml, Instant expiresAt);

    /** The document of {@code clientId}'s rejection, unless it has expired at {@code now}. */
    Optional<String> find(UUID rejectionId, String clientId, Instant now);

    /** Deletes at most {@code limit} rejections expired before {@code now}; returns how many. */
    int deleteExpired(Instant now, int limit);
}
