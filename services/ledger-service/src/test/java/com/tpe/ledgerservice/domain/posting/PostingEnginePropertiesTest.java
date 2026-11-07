package com.tpe.ledgerservice.domain.posting;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.contracts.TechnicalAccounts;
import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import com.tpe.ledgerservice.domain.account.Account;
import com.tpe.ledgerservice.domain.account.AccountKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.Size;

/**
 * Any sequence of engine operations — funding, outgoing and incoming settlement, on-us transfers between customers —
 * keeps Σ DR = Σ CR per entry and {@code booked ≥ 0} on customer accounts (specs/12-ledger-service.md §5, §6.2). A
 * refused operation changes nothing; technical accounts' rows never change.
 */
class PostingEnginePropertiesTest {

    private static final int CUSTOMERS = 3;
    private static final List<UUID> CUSTOMER_IDS = List.of(
            UUID.fromString("0192f0e4-0000-7000-8000-00000000000a"),
            UUID.fromString("0192f0e4-0000-7000-8000-00000000000b"),
            UUID.fromString("0192f0e4-0000-7000-8000-00000000000c"));

    sealed interface Operation {
        JournalEntry entry();
    }

    record Fund(int to, long amount) implements Operation {
        public JournalEntry entry() {
            return JournalEntry.funding(UUID.randomUUID(), CUSTOMER_IDS.get(to), amount, PostingEngineTest.AT);
        }
    }

    record PayOut(int from, long amount) implements Operation {
        public JournalEntry entry() {
            return twoPostings(EntryType.PAYMENT_OUT, CUSTOMER_IDS.get(from), TechnicalAccounts.CSM_SETTLEMENT_ID, amount);
        }
    }

    record PayIn(int to, long amount) implements Operation {
        public JournalEntry entry() {
            return twoPostings(EntryType.PAYMENT_IN, TechnicalAccounts.CSM_SETTLEMENT_ID, CUSTOMER_IDS.get(to), amount);
        }
    }

    record OnUs(int from, int to, long amount) implements Operation {
        public JournalEntry entry() {
            return twoPostings(EntryType.ON_US, CUSTOMER_IDS.get(from), CUSTOMER_IDS.get(to), amount);
        }
    }

    @Property
    void everyEntryBalancesAndNoCustomerAccountGoesBelowZero(
            @ForAll("operations") @Size(max = 60) List<Operation> operations) {
        Map<UUID, Account> accounts = new LinkedHashMap<>();
        CUSTOMER_IDS.forEach(id -> accounts.put(id, PostingEngineTest.customer(id, 0, 0)));
        Account funding = PostingEngineTest.technical(TechnicalAccounts.FUNDING_ID);
        Account csm = PostingEngineTest.technical(TechnicalAccounts.CSM_SETTLEMENT_ID);
        accounts.put(funding.accountId(), funding);
        accounts.put(csm.accountId(), csm);
        List<Posting> booked = new ArrayList<>();

        for (Operation operation : operations) {
            JournalEntry entry = operation.entry();
            assertThat(total(entry.postings(), Side.DR)).isEqualTo(total(entry.postings(), Side.CR));
            assertThat(entry.postings()).hasSizeGreaterThanOrEqualTo(2);
            Map<UUID, Account> before = Map.copyOf(accounts);
            try {
                PostingEngine.post(entry, accounts.values()).forEach(a -> accounts.put(a.accountId(), a));
                booked.addAll(entry.postings());
            } catch (LedgerException e) {
                assertThat(e.error()).isEqualTo(LedgerError.INSUFFICIENT_FUNDS);
                assertThat(accounts).isEqualTo(before);
            }
            accounts.values().stream().filter(a -> a.kind() == AccountKind.CUSTOMER)
                    .forEach(a -> assertThat(a.bookedMinor()).isNotNegative());
        }

        assertThat(accounts.get(TechnicalAccounts.FUNDING_ID)).isEqualTo(funding);
        assertThat(accounts.get(TechnicalAccounts.CSM_SETTLEMENT_ID)).isEqualTo(csm);
        // each customer's booked balance is Σ CR − Σ DR of its postings
        CUSTOMER_IDS.forEach(id -> {
            List<Posting> own = booked.stream().filter(p -> p.accountId().equals(id)).toList();
            assertThat(accounts.get(id).bookedMinor()).isEqualTo(total(own, Side.CR) - total(own, Side.DR));
        });
    }

    @Provide
    Arbitrary<List<Operation>> operations() {
        Arbitrary<Integer> customer = Arbitraries.integers().between(0, CUSTOMERS - 1);
        Arbitrary<Long> amount = Arbitraries.longs().between(1, 1_000_000);
        Arbitrary<Operation> operation = Arbitraries.oneOf(
                Combinators.combine(customer, amount).as(Fund::new),
                Combinators.combine(customer, amount).as(PayOut::new),
                Combinators.combine(customer, amount).as(PayIn::new),
                Combinators.combine(customer, customer, amount).filter((from, to, a) -> !from.equals(to))
                        .as(OnUs::new));
        return operation.list();
    }

    private static JournalEntry twoPostings(EntryType type, UUID debit, UUID credit, long amount) {
        return new JournalEntry(UUID.randomUUID(), type, null, null, PostingEngineTest.AT,
                List.of(new Posting(debit, Side.DR, amount), new Posting(credit, Side.CR, amount)));
    }

    private static long total(List<Posting> postings, Side side) {
        return postings.stream().filter(p -> p.side() == side).mapToLong(Posting::amountMinor).sum();
    }
}
