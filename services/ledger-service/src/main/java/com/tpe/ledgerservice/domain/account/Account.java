package com.tpe.ledgerservice.domain.account;

import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import com.tpe.paymentdomain.Iban;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A ledger account (specs/12-ledger-service.md §5). Balances are EUR minor units; {@code bookedMinor} and
 * {@code heldMinor} are kept on customer accounts only. {@code revision} counts the lifecycle changes published on
 * {@code accounts.events}, {@code version} is the optimistic lock. Technical accounts have no customer and no holder.
 *
 * @param activeBlockReasons the reasons of the active blocks, in declaration order; {@code BLOCKED} while not empty
 */
public record Account(
        UUID accountId,
        Iban iban,
        AccountKind kind,
        CustomerId customerId,
        Holder holder,
        AccountStatus status,
        List<BlockReason> activeBlockReasons,
        String currency,
        long bookedMinor,
        long heldMinor,
        long version,
        long revision,
        Instant openedAt,
        Instant closedAt) {

    /** Every account is in euro (A3); the ledger reads the currency and refuses any other, never assumes it (00 §5). */
    public static final String EUR = "EUR";

    public Account {
        if (!EUR.equals(currency)) {
            throw new IllegalArgumentException("unsupported account currency");
        }
        activeBlockReasons = activeBlockReasons == null || activeBlockReasons.isEmpty()
                ? List.of()
                : List.copyOf(EnumSet.copyOf(activeBlockReasons));
    }

    /** A new customer account: {@code OPEN}, zero balances, revision 1. */
    public static Account openCustomer(UUID accountId, Iban iban, CustomerId customerId, Holder holder,
            Instant openedAt) {
        if (customerId == null) {
            throw new LedgerException(LedgerError.CUSTOMER_ID_MISSING, "a customer account needs a customer number");
        }
        return new Account(accountId, iban, AccountKind.CUSTOMER, customerId, holder, AccountStatus.OPEN, List.of(),
                EUR, 0, 0, 0, 1, openedAt, null);
    }

    /** {@code booked − Σ active holds} (12 §3.3). */
    public long availableMinor() {
        return bookedMinor - heldMinor;
    }

    /**
     * This customer account with {@code changeMinor} booked (positive for a net credit); the version stays, the
     * repository bumps it when it writes the row (12 §6.2).
     *
     * @throws LedgerException {@code INSUFFICIENT_FUNDS} when a net debit exceeds the available balance
     */
    public Account book(long changeMinor) {
        if (kind != AccountKind.CUSTOMER) {
            throw new IllegalStateException("a technical account's row is never booked (ADR-005)");
        }
        long booked = Math.addExact(bookedMinor, changeMinor);
        if (booked < heldMinor) {
            throw new LedgerException(LedgerError.INSUFFICIENT_FUNDS, "the available balance does not cover the debit");
        }
        return new Account(accountId, iban, kind, customerId, holder, status, activeBlockReasons, currency, booked,
                heldMinor, version, revision, openedAt, closedAt);
    }

    /**
     * This customer account with a hold of {@code amountMinor} more (12 §3.1, ADR-005): available goes down, booked
     * stays. The version stays; the repository bumps it when it writes the row.
     *
     * @throws LedgerException {@code CUSTOMER_ID_MISSING}, {@code ACCOUNT_BLOCKED}, {@code ACCOUNT_CLOSED},
     *     {@code INSUFFICIENT_FUNDS}
     */
    public Account placeHold(long amountMinor) {
        if (kind != AccountKind.CUSTOMER) {
            throw new IllegalStateException("technical accounts take no holds (ADR-005)");
        }
        requireOpenWithCustomerNumber();
        if (availableMinor() < amountMinor) {
            throw new LedgerException(LedgerError.INSUFFICIENT_FUNDS, "the available balance does not cover the hold");
        }
        return withHeld(Math.addExact(heldMinor, amountMinor));
    }

    /**
     * This account, if it may be credited by an on-us settlement (12 §3.1): a customer account with a customer number,
     * {@code OPEN}. The caller has checked it is a customer account.
     *
     * @throws LedgerException {@code CUSTOMER_ID_MISSING}, {@code ACCOUNT_BLOCKED}, {@code ACCOUNT_CLOSED}
     */
    public Account requireOnUsPayee() {
        if (kind != AccountKind.CUSTOMER) {
            throw new IllegalStateException("a technical account is never an on-us payee");
        }
        requireOpenWithCustomerNumber();
        return this;
    }

    /** {@link #requireOnUsPayee} as a question: whether this account may be credited on-us now. */
    public boolean isOnUsPayee() {
        return kind == AccountKind.CUSTOMER && customerId != null && status == AccountStatus.OPEN;
    }

    private void requireOpenWithCustomerNumber() {
        if (customerId == null) {
            // Q-02: never move money of an account sanctions screening cannot group by customer
            throw new LedgerException(LedgerError.CUSTOMER_ID_MISSING, "the account has no customer number");
        }
        switch (status) {
            case BLOCKED -> throw new LedgerException(LedgerError.ACCOUNT_BLOCKED, "the account is blocked");
            case CLOSED -> throw new LedgerException(LedgerError.ACCOUNT_CLOSED, "the account is closed");
            case OPEN -> { }
        }
    }

    /**
     * This customer account with a hold of {@code amountMinor} less: the money is available again. No status check:
     * a release is a compensation (12 §3.1).
     */
    public Account releaseHold(long amountMinor) {
        if (amountMinor > heldMinor) {
            throw new IllegalStateException("releasing more than is held");
        }
        return withHeld(heldMinor - amountMinor);
    }

    private Account withHeld(long held) {
        return new Account(accountId, iban, kind, customerId, holder, status, activeBlockReasons, currency, bookedMinor,
                held, version, revision, openedAt, closedAt);
    }

    /**
     * Adds a block of {@code reason} (12 §3.2): the account is {@code BLOCKED}, the revision one higher. A reason
     * already active is a no-op; {@code SANCTIONS} replaces an active {@code SANCTIONS_PROVISIONAL} in the same change.
     *
     * @throws LedgerException {@code ACCOUNT_CLOSED}
     */
    public BlockChange block(BlockReason reason) {
        requireOpenToBlocks();
        if (activeBlockReasons.contains(reason)) {
            return new BlockChange(this, false, null);
        }
        Set<BlockReason> active = mutableReasons();
        BlockReason replaced = null;
        if (reason == BlockReason.SANCTIONS && active.remove(BlockReason.SANCTIONS_PROVISIONAL)) {
            replaced = BlockReason.SANCTIONS_PROVISIONAL;
        }
        active.add(reason);
        return new BlockChange(withBlocks(active), true, replaced);
    }

    /**
     * Lifts the block of {@code reason} (12 §3.2): the account is {@code OPEN} again once no block is left, the
     * revision one higher. A reason that is not active is a no-op.
     *
     * @throws LedgerException {@code ACCOUNT_CLOSED}
     */
    public BlockChange unblock(BlockReason reason) {
        requireOpenToBlocks();
        if (!activeBlockReasons.contains(reason)) {
            return new BlockChange(this, false, null);
        }
        Set<BlockReason> active = mutableReasons();
        active.remove(reason);
        return new BlockChange(withBlocks(active), true, null);
    }

    private void requireOpenToBlocks() {
        if (kind != AccountKind.CUSTOMER) {
            throw new IllegalStateException("technical accounts have no lifecycle");
        }
        if (status == AccountStatus.CLOSED) {
            throw new LedgerException(LedgerError.ACCOUNT_CLOSED, "a closed account cannot be blocked or unblocked");
        }
    }

    private Set<BlockReason> mutableReasons() {
        return activeBlockReasons.isEmpty() ? EnumSet.noneOf(BlockReason.class) : EnumSet.copyOf(activeBlockReasons);
    }

    private Account withBlocks(Set<BlockReason> active) {
        AccountStatus next = active.isEmpty() ? AccountStatus.OPEN : AccountStatus.BLOCKED;
        return new Account(accountId, iban, kind, customerId, holder, next, List.copyOf(active), currency, bookedMinor,
                heldMinor, version, revision + 1, openedAt, closedAt);
    }
}
