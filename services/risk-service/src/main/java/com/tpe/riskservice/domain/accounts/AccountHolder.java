package com.tpe.riskservice.domain.accounts;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * An account holder as ledger-service published it on {@code accounts.events} (specs/14-risk-service.md §4, §5): what
 * the rules and, later, screening need of a customer account, at the account's {@code revision}. {@link #toString()}
 * leaves out names, IBANs and dates of birth, which are personal data (specs/26-observability.md §4.4).
 *
 * @param dateOfBirth set for a {@code PERSON} if ledger has it, else {@code null}
 * @param country the country of residence, if ledger has it, else {@code null}
 * @param organisationId set for an {@code ORGANISATION} that has one, else {@code null}
 */
public record AccountHolder(
        UUID accountId,
        String customerId,
        String iban,
        HolderType holderType,
        String holderName,
        LocalDate dateOfBirth,
        String country,
        String organisationId,
        AccountStatus status,
        Instant openedAt,
        long revision) {

    public AccountHolder {
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(iban, "iban");
        Objects.requireNonNull(holderType, "holderType");
        Objects.requireNonNull(holderName, "holderName");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(openedAt, "openedAt");
    }

    /** Who holds the account (specs/02-messaging-contracts.md §5); {@code UNKNOWN}: a type a newer ledger added. */
    public enum HolderType { PERSON, ORGANISATION, UNKNOWN }

    /** The account's status; {@code UNKNOWN}: a status a newer ledger added, stored, not refused (01 §4). */
    public enum AccountStatus { OPEN, BLOCKED, CLOSED, UNKNOWN }

    @Override
    public String toString() {
        return "AccountHolder[accountId=" + accountId + ", holderType=" + holderType + ", status=" + status
                + ", openedAt=" + openedAt + ", revision=" + revision + "]";
    }
}
