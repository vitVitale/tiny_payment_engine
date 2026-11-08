package com.tpe.ledgerservice.application.reservation;

import com.tpe.contracts.TechnicalAccounts;
import com.tpe.ledgerservice.application.account.AccountRepository;
import com.tpe.ledgerservice.application.posting.JournalRepository;
import com.tpe.ledgerservice.application.posting.OptimisticConflict;
import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import com.tpe.ledgerservice.domain.account.Account;
import com.tpe.ledgerservice.domain.account.AccountKind;
import com.tpe.ledgerservice.domain.posting.JournalEntry;
import com.tpe.ledgerservice.domain.posting.PostingEngine;
import com.tpe.ledgerservice.domain.reservation.PendingCredit;
import com.tpe.paymentdomain.UuidV7Generator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Pending credits on payee accounts for payment-orchestrator (specs/12-ledger-service.md §3.1, ADR-005, ADR-009,
 * ADR-010): the reservation manager's half for incoming payments. A pending credit is a memo in its own table; only
 * its confirm books, Dr CSM settlement account / Cr payee, the payee written version-checked and the CSM settlement
 * account's row only read (no hot row). A cancel of an unknown id leaves a tombstone, so a create that arrives after it
 * is refused.
 */
@Service
public class PendingCreditService {

    private final PendingCreditRepository pendingCredits;
    private final AccountRepository accounts;
    private final JournalRepository journal;
    private final UuidV7Generator ids;
    private final Clock clock;

    public PendingCreditService(PendingCreditRepository pendingCredits, AccountRepository accounts,
            JournalRepository journal, UuidV7Generator ids, Clock clock) {
        this.pendingCredits = pendingCredits;
        this.accounts = accounts;
        this.journal = journal;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Creates an {@code ACTIVE} pending credit, or answers a retry with the existing one; no balance changes. A pending
     * credit already under this id is looked at first, so a retry is answered alike after the account was blocked.
     * The payee row is read {@code FOR SHARE}: the credit writes no account row, so a concurrent block, which updates
     * it, either commits first and the check sees it, or waits until this credit has committed (LED-AC-09).
     *
     * @throws LedgerException {@code CURRENCY_NOT_SUPPORTED}, {@code PENDING_CREDIT_CANCELLED},
     *     {@code PENDING_CREDIT_CONFIRMED}, {@code IDEMPOTENCY_CONFLICT} (the id was used for another account or
     *     amount), {@code ACCOUNT_NOT_FOUND}, {@code ACCOUNT_NOT_CUSTOMER}, {@code CUSTOMER_ID_MISSING},
     *     {@code ACCOUNT_BLOCKED}, {@code ACCOUNT_CLOSED}
     */
    @Transactional
    public PendingCreditCreation create(CreatePendingCredit command) {
        if (!Account.EUR.equals(command.currency())) {
            throw new LedgerException(LedgerError.CURRENCY_NOT_SUPPORTED, "accounts are held in EUR only");
        }
        Optional<PendingCredit> existing = pendingCredits.find(command.pendingCreditId());
        if (existing.isPresent()) {
            return replay(existing.get(), command);
        }
        Account payee = accounts.findByIbanForShare(command.creditorIban())
                .orElseThrow(() -> new LedgerException(LedgerError.ACCOUNT_NOT_FOUND, "no account with this IBAN"));
        if (payee.kind() != AccountKind.CUSTOMER) {
            throw new LedgerException(LedgerError.ACCOUNT_NOT_CUSTOMER, "a technical account");
        }
        payee.requirePayee();
        PendingCredit credit = PendingCredit.active(command.pendingCreditId(), payee.accountId(),
                command.amountMinor(), command.reference(), now());
        // the id first: a cancel that tombstoned it, or a create that took it, in the meantime wins (ADR-009)
        if (!pendingCredits.insertIfAbsent(credit)) {
            return replay(pendingCredits.find(command.pendingCreditId()).orElseThrow(), command);
        }
        return new PendingCreditCreation(credit, true);
    }

    /**
     * Books the pending credit: entry {@code PAYMENT_IN}, Dr CSM settlement account / Cr payee. Does not check the
     * payee's status: the money has settled, and a block restricts debits, not the booking. Already confirmed → the
     * pending credit as it is.
     *
     * @throws LedgerException {@code PENDING_CREDIT_NOT_FOUND}, {@code PENDING_CREDIT_CANCELLED}
     * @throws OptimisticConflict when the payee changed since it was read: run the transaction again
     */
    @Transactional
    public PendingCredit confirm(UUID pendingCreditId) {
        // the pending credit's row first, as a cancel locks it: a concurrent cancel waits, then finds it confirmed
        PendingCredit credit = pendingCredits.findForUpdate(pendingCreditId)
                .orElseThrow(() -> new LedgerException(LedgerError.PENDING_CREDIT_NOT_FOUND,
                        "no pending credit with this id"));
        Instant now = now();
        PendingCredit confirmed = credit.confirm(ids.next(), now);
        if (!credit.active()) {
            return confirmed;
        }
        Account payee = accounts.findById(credit.accountId()).orElseThrow();
        Account csm = accounts.findById(TechnicalAccounts.CSM_SETTLEMENT_ID)
                .orElseThrow(() -> new IllegalStateException("the CSM_SETTLEMENT technical account is missing"));
        JournalEntry entry = JournalEntry.paymentIn(confirmed.entryId(), credit.accountId(), credit.amountMinor(),
                credit.reference().paymentId(), credit.reference().txId(), now);
        List<Account> changed = PostingEngine.post(entry, List.of(csm, payee));
        // pending_credits.entry_id references the entry; the postings' balance check is deferred to the commit
        journal.insert(entry);
        if (!pendingCredits.markConfirmed(confirmed)) {
            throw new IllegalStateException("pending credit " + pendingCreditId + " changed under its row lock");
        }
        for (Account account : changed) {
            if (!accounts.updateBalances(account)) {
                throw new OptimisticConflict("account " + account.accountId() + " changed since it was read");
            }
        }
        return confirmed;
    }

    /**
     * Cancels an {@code ACTIVE} pending credit: the memo goes away, no balance changes. Already {@code CANCELLED} →
     * the pending credit as it is. Unknown id → a tombstone (ADR-009).
     *
     * @throws LedgerException {@code PENDING_CREDIT_CONFIRMED}
     */
    @Transactional
    public PendingCredit cancel(UUID pendingCreditId) {
        Optional<PendingCredit> existing = pendingCredits.findForUpdate(pendingCreditId);
        if (existing.isEmpty()) {
            PendingCredit tombstone = PendingCredit.tombstone(pendingCreditId, now());
            if (pendingCredits.insertIfAbsent(tombstone)) {
                return tombstone;
            }
            // a create (or another cancel) took the id first: cancel what it left
            existing = pendingCredits.findForUpdate(pendingCreditId);
        }
        PendingCredit credit = existing.orElseThrow();
        PendingCredit cancelled = credit.cancel(now());
        if (credit.active() && !pendingCredits.markCancelled(cancelled)) {
            throw new IllegalStateException("pending credit " + pendingCreditId + " changed under its row lock");
        }
        return cancelled;
    }

    /** @throws LedgerException {@code PENDING_CREDIT_NOT_FOUND} */
    @Transactional(readOnly = true)
    public PendingCredit get(UUID pendingCreditId) {
        return pendingCredits.find(pendingCreditId)
                .orElseThrow(() -> new LedgerException(LedgerError.PENDING_CREDIT_NOT_FOUND,
                        "no pending credit with this id"));
    }

    /**
     * A create of an id that already has a pending credit: a cancelled, tombstoned or confirmed one refuses whatever
     * the request; an {@code ACTIVE} one is the answer to a retry for the same account and amount.
     */
    private PendingCreditCreation replay(PendingCredit existing, CreatePendingCredit command) {
        UUID accountId = !existing.active() ? null
                : accounts.findByIban(command.creditorIban()).map(Account::accountId).orElse(null);
        return new PendingCreditCreation(existing.replayFor(accountId, command.amountMinor()), false);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
