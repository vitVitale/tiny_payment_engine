package com.tpe.ledgerservice.domain.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.contracts.TechnicalAccounts;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The engine's own balance check (specs/12-ledger-service.md §5 balanced-entry guard, §6.2), before any insert. */
class JournalEntryTest {

    private static final Instant AT = Instant.parse("2026-01-05T08:00:00Z");
    private static final UUID CUSTOMER = UUID.fromString("0192f0e4-0000-7000-8000-000000000001");
    private static final UUID OTHER = UUID.fromString("0192f0e4-0000-7000-8000-000000000002");

    @Test
    void fundingDebitsTheFundingAccountAndCreditsTheCustomer() {
        UUID entryId = UUID.randomUUID();

        JournalEntry entry = JournalEntry.funding(entryId, CUSTOMER, 100_000, AT);

        assertThat(entry.entryId()).isEqualTo(entryId);
        assertThat(entry.type()).isEqualTo(EntryType.FUNDING);
        assertThat(entry.createdAt()).isEqualTo(AT);
        assertThat(entry.postings()).containsExactly(
                new Posting(TechnicalAccounts.FUNDING_ID, Side.DR, 100_000),
                new Posting(CUSTOMER, Side.CR, 100_000));
    }

    @Test
    void aSettledHoldDebitsThePayerAndCreditsTheCsmSettlementAccount() {
        UUID entryId = UUID.randomUUID();
        UUID paymentId = UUID.randomUUID();

        JournalEntry entry = JournalEntry.paymentOut(entryId, CUSTOMER, 12_345, paymentId, "TPE-TX-1", AT);

        assertThat(entry.type()).isEqualTo(EntryType.PAYMENT_OUT);
        assertThat(entry.paymentId()).isEqualTo(paymentId);
        assertThat(entry.txId()).isEqualTo("TPE-TX-1");
        assertThat(entry.postings()).containsExactly(
                new Posting(CUSTOMER, Side.DR, 12_345),
                new Posting(TechnicalAccounts.CSM_SETTLEMENT_ID, Side.CR, 12_345));
    }

    @Test
    void anOnUsPaymentDebitsThePayerAndCreditsThePayee() {
        UUID paymentId = UUID.randomUUID();

        JournalEntry entry = JournalEntry.onUs(UUID.randomUUID(), CUSTOMER, OTHER, 500, paymentId, "TPE-TX-2", AT);

        assertThat(entry.type()).isEqualTo(EntryType.ON_US);
        assertThat(entry.paymentId()).isEqualTo(paymentId);
        assertThat(entry.postings()).containsExactly(
                new Posting(CUSTOMER, Side.DR, 500),
                new Posting(OTHER, Side.CR, 500));
        assertThat(entry.creditedAccount()).isEqualTo(OTHER);
    }

    @Test
    void anEntryWhosePostingsDoNotBalanceIsRefused() {
        var postings = List.of(new Posting(CUSTOMER, Side.DR, 100), new Posting(OTHER, Side.CR, 99));

        assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), EntryType.ON_US, null, null, AT, postings))
                .isInstanceOf(UnbalancedEntryException.class)
                .hasMessageContaining("DR 100").hasMessageContaining("CR 99");
    }

    @Test
    void anEntryWithFewerThanTwoPostingsIsRefused() {
        assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), EntryType.ON_US, null, null, AT,
                List.of(new Posting(CUSTOMER, Side.CR, 100))))
                .isInstanceOf(UnbalancedEntryException.class);
        assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), EntryType.ON_US, null, null, AT, List.of()))
                .isInstanceOf(UnbalancedEntryException.class);
    }

    @Test
    void anEntryOfOnlyDebitsIsRefused() {
        var postings = List.of(new Posting(CUSTOMER, Side.DR, 100), new Posting(OTHER, Side.DR, 100));

        assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), EntryType.ON_US, null, null, AT, postings))
                .isInstanceOf(UnbalancedEntryException.class);
    }

    @Test
    void sumsThatWouldOverflowAreRefusedNotWrapped() {
        var postings = List.of(
                new Posting(CUSTOMER, Side.DR, Long.MAX_VALUE),
                new Posting(CUSTOMER, Side.DR, 2),
                new Posting(OTHER, Side.CR, 1));

        assertThatThrownBy(() -> new JournalEntry(UUID.randomUUID(), EntryType.ON_US, null, null, AT, postings))
                .isInstanceOf(UnbalancedEntryException.class);
    }

    @Test
    void aPostingAmountMustBePositive() {
        assertThatIllegalArgumentException().isThrownBy(() -> new Posting(CUSTOMER, Side.CR, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new Posting(CUSTOMER, Side.CR, -1));
    }

    @Test
    void thePostingsAreCopied() {
        var postings = new java.util.ArrayList<>(List.of(
                new Posting(CUSTOMER, Side.DR, 5), new Posting(OTHER, Side.CR, 5)));
        JournalEntry entry = new JournalEntry(UUID.randomUUID(), EntryType.ON_US, null, null, AT, postings);

        postings.add(new Posting(OTHER, Side.CR, 1));

        assertThat(entry.postings()).hasSize(2);
    }
}
