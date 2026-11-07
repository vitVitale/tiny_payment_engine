package com.tpe.ledgerservice.application.reservation;

import com.tpe.ledgerservice.application.account.AccountRepository;
import com.tpe.ledgerservice.application.posting.OptimisticConflict;
import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import com.tpe.ledgerservice.domain.account.Account;
import com.tpe.ledgerservice.domain.account.AccountKind;
import com.tpe.ledgerservice.domain.reservation.Hold;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Holds on payer accounts for payment-orchestrator (specs/12-ledger-service.md §3.1, ADR-005, ADR-009): the
 * reservation manager's half for outgoing payments. A hold changes the payer's {@code held_minor}, written
 * version-checked (§6.2), so the status and balance checks cannot be outdated by a concurrent block or hold. A release
 * of an unknown id leaves a tombstone, so a create that arrives after it is refused.
 */
@Service
public class HoldService {

    private final HoldRepository holds;
    private final AccountRepository accounts;
    private final Clock clock;

    public HoldService(HoldRepository holds, AccountRepository accounts, Clock clock) {
        this.holds = holds;
        this.accounts = accounts;
        this.clock = clock;
    }

    /**
     * Creates an {@code ACTIVE} hold, or answers a retry with the existing one. A hold already under this id is looked
     * at first, so a retry is answered alike after the account was blocked or spent.
     *
     * @throws LedgerException {@code CURRENCY_NOT_SUPPORTED}, {@code HOLD_RELEASED}, {@code HOLD_SETTLED},
     *     {@code IDEMPOTENCY_CONFLICT} (the id was used for another account or amount), {@code ACCOUNT_NOT_FOUND},
     *     {@code ACCOUNT_NOT_CUSTOMER}, {@code CUSTOMER_ID_MISSING}, {@code ACCOUNT_BLOCKED}, {@code ACCOUNT_CLOSED},
     *     {@code INSUFFICIENT_FUNDS}
     * @throws OptimisticConflict when the account changed since it was read: run the transaction again
     */
    @Transactional
    public HoldCreation create(CreateHold command) {
        if (!Account.EUR.equals(command.currency())) {
            throw new LedgerException(LedgerError.CURRENCY_NOT_SUPPORTED, "accounts are held in EUR only");
        }
        Optional<Hold> existing = holds.find(command.holdId());
        if (existing.isPresent()) {
            return replay(existing.get(), command);
        }
        Account account = accounts.findByIban(command.debtorIban())
                .orElseThrow(() -> new LedgerException(LedgerError.ACCOUNT_NOT_FOUND, "no account with this IBAN"));
        if (account.kind() != AccountKind.CUSTOMER) {
            throw new LedgerException(LedgerError.ACCOUNT_NOT_CUSTOMER, "a technical account");
        }
        Account held = account.placeHold(command.amountMinor());
        Hold hold = Hold.active(command.holdId(), account.accountId(), command.amountMinor(), command.paymentId(),
                command.txId(), now());
        // the id first: a release that tombstoned it, or a create that took it, in the meantime wins (ADR-009)
        if (!holds.insertIfAbsent(hold)) {
            return replay(holds.find(command.holdId()).orElseThrow(), command);
        }
        if (!accounts.updateBalances(held)) {
            throw new OptimisticConflict("account " + account.accountId() + " changed since it was read");
        }
        return new HoldCreation(hold, true);
    }

    /**
     * Releases an {@code ACTIVE} hold: its amount is available again, whatever the account's status (a
     * compensation). Already {@code RELEASED} → the hold as it is. Unknown id → a tombstone (ADR-009).
     *
     * @throws LedgerException {@code HOLD_SETTLED}
     * @throws OptimisticConflict when the account or the hold changed since they were read: run the transaction again
     */
    @Transactional
    public Hold release(UUID holdId) {
        Optional<Hold> existing = holds.find(holdId);
        if (existing.isEmpty()) {
            Hold tombstone = Hold.tombstone(holdId, now());
            if (holds.insertIfAbsent(tombstone)) {
                return tombstone;
            }
            // a create (or another release) took the id first: release what it left
            existing = holds.find(holdId);
        }
        Hold hold = existing.orElseThrow();
        Hold released = hold.release(now());
        if (!hold.active()) {
            return released;
        }
        Account account = accounts.findById(hold.accountId()).orElseThrow();
        if (!holds.markReleased(released)
                || !accounts.updateBalances(account.releaseHold(hold.amountMinor()))) {
            throw new OptimisticConflict("hold " + holdId + " or its account changed since they were read");
        }
        return released;
    }

    /** @throws LedgerException {@code HOLD_NOT_FOUND} */
    @Transactional(readOnly = true)
    public Hold get(UUID holdId) {
        return holds.find(holdId)
                .orElseThrow(() -> new LedgerException(LedgerError.HOLD_NOT_FOUND, "no hold with this id"));
    }

    /**
     * A create of an id that already has a hold: a released, tombstoned or settled one refuses whatever the request;
     * an {@code ACTIVE} one is the answer to a retry for the same account and amount.
     */
    private HoldCreation replay(Hold existing, CreateHold command) {
        UUID accountId = !existing.active() ? null
                : accounts.findByIban(command.debtorIban()).map(Account::accountId).orElse(null);
        return new HoldCreation(existing.replayFor(accountId, command.amountMinor()), false);
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }
}
