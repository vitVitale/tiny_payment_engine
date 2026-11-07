package com.tpe.ledgerservice.domain.account;

import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import com.tpe.paymentdomain.Iban;
import java.time.Instant;
import java.util.UUID;

/**
 * A ledger account (specs/12-ledger-service.md §5). Balances are EUR minor units; {@code bookedMinor} and
 * {@code heldMinor} are kept on customer accounts only. {@code revision} counts the lifecycle changes published on
 * {@code accounts.events}, {@code version} is the optimistic lock. Technical accounts have no customer and no holder.
 */
public record Account(
        UUID accountId,
        Iban iban,
        AccountKind kind,
        CustomerId customerId,
        Holder holder,
        AccountStatus status,
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
    }

    /** A new customer account: {@code OPEN}, zero balances, revision 1. */
    public static Account openCustomer(UUID accountId, Iban iban, CustomerId customerId, Holder holder,
            Instant openedAt) {
        if (customerId == null) {
            throw new LedgerException(LedgerError.CUSTOMER_ID_MISSING, "a customer account needs a customer number");
        }
        return new Account(accountId, iban, AccountKind.CUSTOMER, customerId, holder, AccountStatus.OPEN, EUR,
                0, 0, 0, 1, openedAt, null);
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
        return new Account(accountId, iban, kind, customerId, holder, status, currency, booked, heldMinor, version,
                revision, openedAt, closedAt);
    }
}
