package com.tpe.ledgerservice.domain.posting;

import com.tpe.contracts.TechnicalAccounts;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A balanced journal entry (specs/12-ledger-service.md §5, ADR-005): at least 2 postings with Σ DR = Σ CR. The engine
 * checks this here, before insert; the deferred constraint trigger on {@code postings} is the safety net.
 *
 * @param paymentId the payment it settles, {@code null} for funding
 * @param txId the payment's {@code TxId}, {@code null} for funding
 */
public record JournalEntry(
        UUID entryId,
        EntryType type,
        UUID paymentId,
        String txId,
        Instant createdAt,
        List<Posting> postings) {

    public JournalEntry {
        Objects.requireNonNull(entryId, "entryId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(createdAt, "createdAt");
        postings = List.copyOf(postings);
        if (postings.size() < 2) {
            throw new UnbalancedEntryException("a journal entry needs at least 2 postings, has " + postings.size());
        }
        long debits;
        long credits;
        try {
            debits = total(postings, Side.DR);
            credits = total(postings, Side.CR);
        } catch (ArithmeticException e) {
            throw new UnbalancedEntryException("the postings' total overflows");
        }
        if (debits != credits) {
            throw new UnbalancedEntryException("postings do not balance: DR " + debits + ", CR " + credits);
        }
    }

    /** Test funding (UC-10 3): Dr FUNDING technical account / Cr customer. */
    public static JournalEntry funding(UUID entryId, UUID customerAccountId, long amountMinor, Instant createdAt) {
        return new JournalEntry(entryId, EntryType.FUNDING, null, null, createdAt, List.of(
                new Posting(TechnicalAccounts.FUNDING_ID, Side.DR, amountMinor),
                new Posting(customerAccountId, Side.CR, amountMinor)));
    }

    private static long total(List<Posting> postings, Side side) {
        long total = 0;
        for (Posting posting : postings) {
            if (posting.side() == side) {
                total = Math.addExact(total, posting.amountMinor());
            }
        }
        return total;
    }
}
