package com.tpe.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tpe.paymentdomain.Iban;
import java.time.Instant;
import java.util.List;

/**
 * The full current state of a published account (specs/02-messaging-contracts.md §5). {@code customerId} is required:
 * risk-service groups accounts by customer. Technical accounts ({@code kind = TECHNICAL}) are never published.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountSnapshot(
        Iban iban,
        String customerId,
        AccountStatus status,
        List<BlockReason> activeBlockReasons,
        AccountKind kind,
        Holder holder,
        Instant openedAt,
        @OptionalField Instant closedAt) {

    public AccountSnapshot {
        activeBlockReasons = activeBlockReasons == null ? null : List.copyOf(activeBlockReasons);
    }
}
