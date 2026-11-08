package com.tpe.ledgerservice.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@code held_minor} under holds (specs/12-ledger-service.md §3.1, §3.3, §6.2): available = booked − held. */
class AccountHoldTest {

    @Test
    void aHoldReducesAvailableButNotBooked() {
        Account account = account(AccountStatus.OPEN, new CustomerId("C1"), 1_000, 200);

        Account held = account.placeHold(300);

        assertThat(held.bookedMinor()).isEqualTo(1_000);
        assertThat(held.heldMinor()).isEqualTo(500);
        assertThat(held.availableMinor()).isEqualTo(500);
        assertThat(held.version()).isEqualTo(account.version());
        assertThat(held.revision()).isEqualTo(account.revision());
    }

    @Test
    void aHoldOfExactlyTheAvailableBalanceIsPlacedAndOneMoreIsInsufficientFunds() {
        Account account = account(AccountStatus.OPEN, new CustomerId("C1"), 1_000, 200);

        assertThat(account.placeHold(800).availableMinor()).isZero();
        assertRefused(() -> account.placeHold(801), LedgerError.INSUFFICIENT_FUNDS);
    }

    @Test
    void onlyAnOpenAccountWithACustomerNumberTakesAHold() {
        assertRefused(() -> account(AccountStatus.BLOCKED, new CustomerId("C1"), 1_000, 0).placeHold(1),
                LedgerError.ACCOUNT_BLOCKED);
        assertRefused(() -> account(AccountStatus.CLOSED, new CustomerId("C1"), 1_000, 0).placeHold(1),
                LedgerError.ACCOUNT_CLOSED);
        assertRefused(() -> account(AccountStatus.OPEN, null, 1_000, 0).placeHold(1),
                LedgerError.CUSTOMER_ID_MISSING);
    }

    @Test
    void releasingAHoldRestoresAvailable() {
        Account account = account(AccountStatus.OPEN, new CustomerId("C1"), 1_000, 300);

        Account released = account.releaseHold(300);

        assertThat(released.heldMinor()).isZero();
        assertThat(released.availableMinor()).isEqualTo(1_000);
    }

    /** A compensation: the money is the customer's again whatever the account's status. */
    @Test
    void aHoldIsReleasedAlsoOnABlockedAccount() {
        Account blocked = account(AccountStatus.BLOCKED, new CustomerId("C1"), 1_000, 300);

        assertThat(blocked.releaseHold(300).heldMinor()).isZero();
    }

    @Test
    void anOpenCustomerAccountWithACustomerNumberTakesAnOnUsCredit() {
        Account payee = account(AccountStatus.OPEN, new CustomerId("C2"), 0, 0);

        assertThat(payee.requirePayee()).isSameAs(payee);
        assertThat(payee.isPayee()).isTrue();
    }

    @Test
    void aBlockedClosedOrUnnumberedPayeeRefusesAnOnUsCredit() {
        assertRefused(() -> account(AccountStatus.BLOCKED, new CustomerId("C2"), 0, 0).requirePayee(),
                LedgerError.ACCOUNT_BLOCKED);
        assertRefused(() -> account(AccountStatus.CLOSED, new CustomerId("C2"), 0, 0).requirePayee(),
                LedgerError.ACCOUNT_CLOSED);
        assertRefused(() -> account(AccountStatus.OPEN, null, 0, 0).requirePayee(),
                LedgerError.CUSTOMER_ID_MISSING);
        assertThat(account(AccountStatus.BLOCKED, new CustomerId("C2"), 0, 0).isPayee()).isFalse();
        assertThat(account(AccountStatus.CLOSED, new CustomerId("C2"), 0, 0).isPayee()).isFalse();
        assertThat(account(AccountStatus.OPEN, null, 0, 0).isPayee()).isFalse();
    }

    @Test
    void releasingMoreThanIsHeldIsAProgrammingError() {
        Account account = account(AccountStatus.OPEN, new CustomerId("C1"), 1_000, 300);

        assertThatThrownBy(() -> account.releaseHold(301)).isInstanceOf(IllegalStateException.class);
    }

    private static Account account(AccountStatus status, CustomerId customerId, long booked, long held) {
        return new Account(UUID.randomUUID(), new IbanGenerator("10010010").generate(9), AccountKind.CUSTOMER,
                customerId, Holder.of(HolderType.PERSON, "Anna Schmidt", null, null, null), status,
                status == AccountStatus.BLOCKED ? List.of(BlockReason.OPERATOR) : List.of(), Account.EUR, booked,
                held, 0, 2, 1, Instant.EPOCH, status == AccountStatus.CLOSED ? Instant.EPOCH : null);
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, LedgerError error) {
        assertThatThrownBy(call).isInstanceOfSatisfying(LedgerException.class,
                e -> assertThat(e.error()).isEqualTo(error));
    }
}
