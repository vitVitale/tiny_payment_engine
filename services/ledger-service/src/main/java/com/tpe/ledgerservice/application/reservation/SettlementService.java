package com.tpe.ledgerservice.application.reservation;

import com.tpe.contracts.TechnicalAccounts;
import com.tpe.ledgerservice.application.account.AccountRepository;
import com.tpe.ledgerservice.application.posting.JournalRepository;
import com.tpe.ledgerservice.application.posting.OptimisticConflict;
import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import com.tpe.ledgerservice.domain.account.Account;
import com.tpe.ledgerservice.domain.account.AccountKind;
import com.tpe.ledgerservice.domain.posting.EntryType;
import com.tpe.ledgerservice.domain.posting.JournalEntry;
import com.tpe.ledgerservice.domain.posting.PostingEngine;
import com.tpe.ledgerservice.domain.reservation.Hold;
import com.tpe.ledgerservice.domain.reservation.HoldStatus;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.UuidV7Generator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Settles holds for payment-orchestrator (specs/12-ledger-service.md §3.1, §6.2, ADR-005): the held money leaves the
 * payer in one journal entry, to the CSM settlement account or, on-us, to the payee. The payer's hold and debit go in
 * one version-checked write; the CSM settlement account's row is only read (no hot row). Rows are locked in the order
 * hold, then accounts, as create and release write them, so a settle and a release of the same hold cannot deadlock.
 */
@Service
public class SettlementService {

    private final HoldRepository holds;
    private final AccountRepository accounts;
    private final JournalRepository journal;
    private final UuidV7Generator ids;
    private final Clock clock;

    public SettlementService(HoldRepository holds, AccountRepository accounts, JournalRepository journal,
            UuidV7Generator ids, Clock clock) {
        this.holds = holds;
        this.accounts = accounts;
        this.journal = journal;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Entry {@code PAYMENT_OUT}, Dr payer / Cr CSM settlement account. Does not check the payer's status: the CSM has
     * already settled. Already settled this way → the same result.
     *
     * @throws LedgerException {@code HOLD_NOT_FOUND}, {@code HOLD_RELEASED}, {@code HOLD_SETTLED} (settled on-us)
     * @throws OptimisticConflict when the payer changed since it was read: run the transaction again
     */
    @Transactional
    public Settlement settle(UUID holdId) {
        Hold hold = lock(holdId);
        if (hold.status() == HoldStatus.SETTLED) {
            settledBy(hold, EntryType.PAYMENT_OUT);
            return new Settlement(hold, null);
        }
        Instant now = now();
        Hold settled = hold.settle(ids.next(), now);
        Account payer = accounts.findById(hold.accountId()).orElseThrow();
        Account csm = accounts.findById(TechnicalAccounts.CSM_SETTLEMENT_ID)
                .orElseThrow(() -> new IllegalStateException("the CSM_SETTLEMENT technical account is missing"));
        JournalEntry entry = JournalEntry.paymentOut(settled.entryId(), hold.accountId(), hold.amountMinor(),
                hold.paymentId(), hold.txId(), now);
        book(settled, entry, List.of(payer.releaseHold(hold.amountMinor()), csm));
        return new Settlement(settled, null);
    }

    /**
     * Entry {@code ON_US}, Dr payer / Cr payee, after checking the payee in the same transaction. A refusal leaves the
     * hold {@code ACTIVE} (the orchestrator releases it). Both customer rows are locked in ascending
     * {@code account_id} order, then written version-checked (12 §6.2). Already settled on-us to this payee → the same
     * result.
     *
     * @throws LedgerException {@code HOLD_NOT_FOUND}, {@code HOLD_RELEASED}, {@code HOLD_SETTLED} (settled to the CSM),
     *     {@code IDEMPOTENCY_CONFLICT} (settled on-us to another payee), {@code ACCOUNT_NOT_FOUND},
     *     {@code ACCOUNT_NOT_CUSTOMER}, {@code CUSTOMER_ID_MISSING}, {@code ACCOUNT_BLOCKED}, {@code ACCOUNT_CLOSED}
     * @throws OptimisticConflict when the payee was blocked or closed meanwhile, or an account changed since it
     *     was read: run the transaction again
     */
    @Transactional
    public Settlement settleOnUs(UUID holdId, Iban creditorIban) {
        Hold hold = lock(holdId);
        if (hold.status() == HoldStatus.SETTLED) {
            UUID creditor = settledBy(hold, EntryType.ON_US).creditedAccount();
            UUID requestedPayee = accounts.findByIban(creditorIban).map(Account::accountId).orElse(null);
            if (!creditor.equals(requestedPayee)) {
                throw new LedgerException(LedgerError.IDEMPOTENCY_CONFLICT, "the hold was settled to another payee");
            }
            return new Settlement(hold, creditor);
        }
        Instant now = now();
        Hold settled = hold.settle(ids.next(), now);
        Account payee = accounts.findByIban(creditorIban)
                .orElseThrow(() -> new LedgerException(LedgerError.ACCOUNT_NOT_FOUND, "no account with this IBAN"));
        if (payee.kind() != AccountKind.CUSTOMER) {
            throw new LedgerException(LedgerError.ACCOUNT_NOT_CUSTOMER, "a technical account");
        }
        // a refusal commits with its stored 422: nothing is written before this check
        payee.requireOnUsPayee();
        // locked before the postings' foreign keys take FOR KEY SHARE on these rows, which would block an on-us
        // settlement in the other direction from its FOR UPDATE while it blocks ours
        List<Account> locked = accounts.lockForUpdate(Set.of(hold.accountId(), payee.accountId()));
        Account lockedPayer = byId(locked, hold.accountId()).releaseHold(hold.amountMinor());
        Account lockedPayee = byId(locked, payee.accountId());
        if (!lockedPayee.isOnUsPayee()) {
            // blocked or closed since the check above: the next attempt refuses it before any write
            throw new OptimisticConflict("payee " + payee.accountId() + " changed since it was read");
        }
        JournalEntry entry = JournalEntry.onUs(settled.entryId(), hold.accountId(), payee.accountId(),
                hold.amountMinor(), hold.paymentId(), hold.txId(), now);
        book(settled, entry, lockedPayer.accountId().equals(lockedPayee.accountId())
                ? List.of(lockedPayer)
                : List.of(lockedPayer, lockedPayee));
        return new Settlement(settled, payee.accountId());
    }

    /**
     * The hold, its row locked first, as create and release write it first: a concurrent release waits for this
     * settlement, then finds the hold settled.
     */
    private Hold lock(UUID holdId) {
        return holds.findForUpdate(holdId)
                .orElseThrow(() -> new LedgerException(LedgerError.HOLD_NOT_FOUND, "no hold with this id"));
    }

    /**
     * The entry that settled {@code hold}, if it is of {@code type}: a settle repeated the same way.
     *
     * @throws LedgerException {@code HOLD_SETTLED} when the hold was settled the other way
     */
    private JournalEntry settledBy(Hold hold, EntryType type) {
        JournalEntry entry = journal.find(hold.entryId()).orElseThrow();
        if (entry.type() != type) {
            throw new LedgerException(LedgerError.HOLD_SETTLED, "the hold was settled as " + entry.type());
        }
        return entry;
    }

    /**
     * Inserts the entry ({@code holds.entry_id} references it; the postings' balance check is deferred to the commit),
     * marks the hold settled by it and writes the customer accounts version-checked.
     */
    private void book(Hold settled, JournalEntry entry, List<Account> accountsOfEntry) {
        List<Account> changed = PostingEngine.post(entry, accountsOfEntry);
        journal.insert(entry);
        if (!holds.markSettled(settled)) {
            throw new IllegalStateException("hold " + settled.holdId() + " changed under its row lock");
        }
        for (Account account : changed) {
            if (!accounts.updateBalances(account)) {
                throw new OptimisticConflict("account " + account.accountId() + " changed since it was read");
            }
        }
    }

    private static Account byId(List<Account> accounts, UUID accountId) {
        return accounts.stream().filter(account -> account.accountId().equals(accountId)).findFirst().orElseThrow();
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
