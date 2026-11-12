package com.tpe.vopservice.domain.accounts;

import java.util.Objects;

/**
 * A row of the {@code account_holders} projection (specs/13-vop-service.md §5): the holder and its name as
 * normalised when the snapshot was applied. {@link #toString()} leaves out IBAN and names, which are personal data
 * (specs/26-observability.md §4.4).
 */
public record ProjectedHolder(AccountHolder holder, String normalisedName) {

    public ProjectedHolder {
        Objects.requireNonNull(holder, "holder");
        Objects.requireNonNull(normalisedName, "normalisedName");
    }

    @Override
    public String toString() {
        return "ProjectedHolder[accountId=" + holder.accountId() + ", holderType=" + holder.holderType()
                + ", status=" + holder.status() + ", revision=" + holder.revision() + "]";
    }
}
