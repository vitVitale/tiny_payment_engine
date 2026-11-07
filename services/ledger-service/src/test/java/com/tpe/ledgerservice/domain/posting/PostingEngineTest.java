package com.tpe.ledgerservice.domain.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.contracts.TechnicalAccounts;
import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import com.tpe.ledgerservice.domain.account.Account;
import com.tpe.ledgerservice.domain.account.AccountKind;
import com.tpe.ledgerservice.domain.account.AccountStatus;
import com.tpe.ledgerservice.domain.account.CustomerId;
import com.tpe.ledgerservice.domain.account.Holder;
import com.tpe.ledgerservice.domain.account.HolderType;
import com.tpe.paymentdomain.Iban;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** specs/12-ledger-service.md §6.2, ADR-005: customer balances move with postings, technical rows never do. */
class PostingEngineTest {

    static final Instant AT = Instant.parse("2026-01-05T08:00:00Z");

    @Test
    void fundingRaisesBookedAndAvailableOfTheCustomerAndLeavesTheFundingAccountAlone() {
        Account customer = customer(UUID.randomUUID(), 0, 0);
        Account funding = technical(TechnicalAccounts.FUNDING_ID);

        List<Account> changed = PostingEngine.post(
                JournalEntry.funding(UUID.randomUUID(), customer.accountId(), 100_000, AT), List.of(customer, funding));

        assertThat(changed).singleElement().satisfies(account -> {
            assertThat(account.accountId()).isEqualTo(customer.accountId());
            assertThat(account.bookedMinor()).isEqualTo(100_000);
            assertThat(account.availableMinor()).isEqualTo(100_000);
            // the repository bumps the version when it writes the row
            assertThat(account.version()).isEqualTo(customer.version());
        });
    }

    @Test
    void aDebitBeyondTheAvailableBalanceIsInsufficientFunds() {
        Account payer = customer(UUID.randomUUID(), 1_000, 400);
        Account payee = customer(UUID.randomUUID(), 0, 0);
        JournalEntry entry = transfer(payer, payee, 601);

        assertThatThrownBy(() -> PostingEngine.post(entry, List.of(payer, payee)))
                .isInstanceOfSatisfying(LedgerException.class,
                        e -> assertThat(e.error()).isEqualTo(LedgerError.INSUFFICIENT_FUNDS));
    }

    @Test
    void aDebitOfExactlyTheAvailableBalanceIsBooked() {
        Account payer = customer(UUID.randomUUID(), 1_000, 400);
        Account payee = customer(UUID.randomUUID(), 0, 0);

        List<Account> changed = PostingEngine.post(transfer(payer, payee, 600), List.of(payer, payee));

        assertThat(changed).extracting(Account::bookedMinor).containsExactlyInAnyOrder(400L, 600L);
    }

    @Test
    void twoPostingsToOneCustomerAccountAreAppliedTogether() {
        Account account = customer(UUID.randomUUID(), 50, 0);
        Account other = customer(UUID.randomUUID(), 0, 0);
        var entry = new JournalEntry(UUID.randomUUID(), EntryType.ON_US, null, null, AT, List.of(
                new Posting(account.accountId(), Side.CR, 30),
                new Posting(account.accountId(), Side.DR, 70),
                new Posting(other.accountId(), Side.CR, 40)));

        List<Account> changed = PostingEngine.post(entry, List.of(account, other));

        assertThat(changed).filteredOn(a -> a.accountId().equals(account.accountId()))
                .singleElement().extracting(Account::bookedMinor).isEqualTo(10L);
    }

    @Test
    void anAccountOfAPostingMustBeGiven() {
        Account customer = customer(UUID.randomUUID(), 0, 0);

        assertThatIllegalArgumentException().isThrownBy(() -> PostingEngine.post(
                JournalEntry.funding(UUID.randomUUID(), customer.accountId(), 1, AT), List.of(customer)));
    }

    static Account customer(UUID accountId, long booked, long held) {
        return new Account(accountId, new Iban("DE89370400440532013000"), AccountKind.CUSTOMER,
                new CustomerId("CUST-1"), holder(), AccountStatus.OPEN, List.of(), Account.EUR, booked, held, 3, 1,
                AT, null);
    }

    static Account technical(UUID accountId) {
        return new Account(accountId, new Iban("DE89370400440532013000"), AccountKind.TECHNICAL, null, null,
                AccountStatus.OPEN, List.of(), Account.EUR, 0, 0, 0, 1, AT, null);
    }

    private static Holder holder() {
        return Holder.of(HolderType.PERSON, "Anna Schmidt", null, null, null);
    }

    private static JournalEntry transfer(Account from, Account to, long amount) {
        return new JournalEntry(UUID.randomUUID(), EntryType.ON_US, null, null, AT, List.of(
                new Posting(from.accountId(), Side.DR, amount), new Posting(to.accountId(), Side.CR, amount)));
    }
}
