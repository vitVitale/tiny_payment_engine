package com.tpe.contracts;

import java.util.Set;
import java.util.UUID;

/**
 * The fixed ids of ledger-service's technical accounts (specs/12-ledger-service.md §5, ADR-005), created by its seed
 * migration. They never change: other services and the invariant checks refer to them. Technical accounts are never
 * published on {@code accounts.events} (02 §5).
 */
public final class TechnicalAccounts {

    /** Mirrors our position at the CSM: Dr on incoming, Cr on outgoing settlements. */
    public static final UUID CSM_SETTLEMENT_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

    /** The other side of test funding (UC-10 3). */
    public static final UUID FUNDING_ID = UUID.fromString("00000000-0000-7000-8000-000000000002");

    public static final Set<UUID> IDS = Set.of(CSM_SETTLEMENT_ID, FUNDING_ID);

    private TechnicalAccounts() {}
}
