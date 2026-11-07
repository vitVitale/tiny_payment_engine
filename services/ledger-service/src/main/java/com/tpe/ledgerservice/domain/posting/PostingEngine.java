package com.tpe.ledgerservice.domain.posting;

import com.tpe.ledgerservice.domain.account.Account;
import com.tpe.ledgerservice.domain.account.AccountKind;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Applies a journal entry to the balances of the accounts it touches (specs/12-ledger-service.md §6.2, ADR-005).
 * Customer accounts get the change in memory, which the caller writes back version-checked; technical accounts keep
 * their row: their postings are only inserted, their balance is derived (§6.3).
 */
public final class PostingEngine {

    private PostingEngine() {}

    /**
     * @param accounts every account a posting of {@code entry} goes to, as read in the posting's transaction
     * @return the customer accounts with their new balances, in the order of {@code accounts}
     * @throws com.tpe.ledgerservice.domain.LedgerException {@code INSUFFICIENT_FUNDS} when a debit would take a
     *     customer account's booked balance below its holds; nothing is applied then
     * @throws IllegalArgumentException when a posting's account is not among {@code accounts}
     */
    public static List<Account> post(JournalEntry entry, Collection<Account> accounts) {
        Map<UUID, Account> byId = new LinkedHashMap<>();
        accounts.forEach(account -> byId.put(account.accountId(), account));
        Map<UUID, Long> changes = new LinkedHashMap<>();
        for (Posting posting : entry.postings()) {
            Account account = byId.get(posting.accountId());
            if (account == null) {
                throw new IllegalArgumentException("the account of a posting was not given: " + posting.accountId());
            }
            if (account.kind() == AccountKind.CUSTOMER) {
                long signed = posting.side() == Side.CR ? posting.amountMinor() : -posting.amountMinor();
                changes.merge(account.accountId(), signed, Math::addExact);
            }
        }
        List<Account> changed = new ArrayList<>();
        byId.values().stream()
                .filter(account -> changes.containsKey(account.accountId()))
                .forEach(account -> changed.add(account.book(changes.get(account.accountId()))));
        return changed;
    }
}
