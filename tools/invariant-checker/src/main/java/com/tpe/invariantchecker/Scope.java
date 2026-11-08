package com.tpe.invariantchecker;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Which rows a check looks at: a system test's own accounts (specs/20-testing-strategy.md §3, per-test) or the whole
 * database (the soak, the production job).
 */
public final class Scope {

    private static final Scope ALL = new Scope(null);

    private final List<UUID> accountIds;

    private Scope(List<UUID> accountIds) {
        this.accountIds = accountIds;
    }

    /** Only these ledger accounts, and the journal entries that post to them. */
    public static Scope accounts(Collection<UUID> accountIds) {
        return new Scope(List.copyOf(Objects.requireNonNull(accountIds, "accountIds")));
    }

    /** Every row. */
    public static Scope all() {
        return ALL;
    }

    /** The account ids, empty for {@link #all()}. */
    Optional<List<UUID>> accountIds() {
        return Optional.ofNullable(accountIds);
    }
}
