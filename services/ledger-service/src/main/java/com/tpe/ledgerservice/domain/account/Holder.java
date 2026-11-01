package com.tpe.ledgerservice.domain.account;

import com.tpe.paymentdomain.DomainValidationException;
import com.tpe.paymentdomain.PartyName;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The holder of a customer account (specs/12-ledger-service.md §3.2): an {@code ORGANISATION} has an
 * {@code organisationId} (e.g. LEI) and no {@code dateOfBirth}. Use {@link #of}, which reports every broken rule.
 */
public record Holder(
        HolderType type,
        PartyName name,
        LocalDate dateOfBirth,
        String countryOfResidence,
        String organisationId) {

    private static final Pattern COUNTRY = Pattern.compile("[A-Z]{2}");
    private static final int MAX_ORGANISATION_ID = 35;

    /** @throws InvalidAccountDataException with one violation per broken rule */
    public static Holder of(HolderType type, String name, LocalDate dateOfBirth, String countryOfResidence,
            String organisationId) {
        List<AccountDataViolation> violations = new ArrayList<>();
        if (type == null) {
            violations.add(violation("type", "REQUIRED"));
        }
        PartyName partyName = null;
        if (name == null) {
            violations.add(violation("name", "REQUIRED"));
        } else {
            try {
                partyName = new PartyName(name);
            } catch (DomainValidationException e) {
                violations.add(violation("name", e.code().name()));
            }
        }
        if (countryOfResidence != null && !COUNTRY.matcher(countryOfResidence).matches()) {
            violations.add(violation("countryOfResidence", "INVALID_COUNTRY"));
        }
        if (organisationId != null && organisationId.length() > MAX_ORGANISATION_ID) {
            violations.add(violation("organisationId", "TOO_LONG"));
        }
        if (type == HolderType.ORGANISATION) {
            if (organisationId == null || organisationId.isBlank()) {
                violations.add(violation("organisationId", "REQUIRED"));
            }
            if (dateOfBirth != null) {
                violations.add(violation("dateOfBirth", "NOT_ALLOWED"));
            }
        }
        if (!violations.isEmpty()) {
            throw new InvalidAccountDataException(violations);
        }
        return new Holder(type, partyName, dateOfBirth, countryOfResidence, organisationId);
    }

    private static AccountDataViolation violation(String field, String code) {
        return new AccountDataViolation("holder." + field, code);
    }
}
