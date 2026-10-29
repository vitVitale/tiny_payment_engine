package com.tpe.contracts;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tpe.paymentdomain.PartyName;
import java.time.LocalDate;

/**
 * The holder of an account (specs/02-messaging-contracts.md §5, 12 §3.2). An {@code ORGANISATION} has an
 * {@code organisationId} (e.g. LEI) and no {@code dateOfBirth}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Holder(
        HolderType type,
        PartyName name,
        @OptionalField LocalDate dateOfBirth,
        @OptionalField String countryOfResidence,
        @OptionalField String organisationId) {}
