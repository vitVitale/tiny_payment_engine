package com.tpe.vopservice.domain.accounts;

import java.util.Objects;
import java.util.UUID;

/**
 * An account holder as ledger-service published it on {@code accounts.events} (specs/13-vop-service.md §4, §5): what
 * the responder needs to answer a verification, at the account's {@code revision}.
 *
 * @param organisationId set for an {@code ORGANISATION} that has one, else {@code null}
 */
public record AccountHolder(
        UUID accountId,
        String iban,
        HolderType holderType,
        String holderName,
        String organisationId,
        AccountStatus status,
        long revision) {

    public AccountHolder {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(iban, "iban");
        Objects.requireNonNull(holderType, "holderType");
        Objects.requireNonNull(holderName, "holderName");
        Objects.requireNonNull(status, "status");
    }

    /**
     * Who holds the account (specs/02-messaging-contracts.md §5). {@code UNKNOWN}: a type a newer ledger added (01 §4);
     * the row stays current, and only the organisation-id shortcut of 13 §6.2 needs {@code ORGANISATION}.
     */
    public enum HolderType { PERSON, ORGANISATION, UNKNOWN }

    /**
     * The account's status. {@code UNKNOWN}: a status a newer ledger added (01 §4); like {@code CLOSED} it cannot be
     * verified, which 13 §6.2 answers {@code NOT_POSSIBLE}. Stored, not refused, so the partition keeps moving.
     */
    public enum AccountStatus { OPEN, BLOCKED, CLOSED, UNKNOWN }
}
