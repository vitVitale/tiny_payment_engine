package com.tpe.ledgerservice.application.account;

import com.tpe.ledgerservice.application.posting.AccountPosting;
import com.tpe.ledgerservice.application.posting.JournalRepository;
import com.tpe.ledgerservice.application.posting.OptimisticConflict;
import com.tpe.ledgerservice.application.posting.PostingsPage;
import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import com.tpe.ledgerservice.domain.account.Account;
import com.tpe.ledgerservice.domain.account.AccountKind;
import com.tpe.ledgerservice.domain.account.BlockChange;
import com.tpe.ledgerservice.domain.account.BlockReason;
import com.tpe.ledgerservice.domain.account.CustomerId;
import com.tpe.ledgerservice.domain.account.IbanGenerator;
import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.UuidV7Generator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The account service of specs/12-ledger-service.md §2 (UC-10): opens and reads accounts and their postings. Every
 * change commits together with its {@code audit_log} row and its {@code accounts.events} message (ADR-002, ADR-007).
 */
@Service
public class AccountService {

    static final String OPEN = "OPEN";
    static final String BLOCK = "BLOCK";
    static final String UNBLOCK = "UNBLOCK";

    /** Page size of the entries feed (12 §3.2). */
    public static final int ENTRIES_PAGE = 100;

    private final AccountRepository accounts;
    private final JournalRepository journal;
    private final AuditLog audit;
    private final OutboxWriter outbox;
    private final IbanGenerator ibans;
    private final UuidV7Generator ids;
    private final Clock clock;

    public AccountService(AccountRepository accounts, JournalRepository journal, AuditLog audit, OutboxWriter outbox,
            IbanGenerator ibans, UuidV7Generator ids, Clock clock) {
        this.accounts = accounts;
        this.journal = journal;
        this.audit = audit;
        this.outbox = outbox;
        this.ibans = ibans;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * Opens a customer account with a new IBAN and publishes {@code AccountOpened} with revision 1 (LED-AC-01).
     *
     * @param actor the JWT {@code sub} of the operator
     * @throws LedgerException {@code CUSTOMER_ID_MISSING}
     */
    @Transactional
    public Account open(OpenAccount command, String actor) {
        if (command.customerId() == null) {
            // before nextval, which a rollback does not return: a refused open uses up no account number
            throw new LedgerException(LedgerError.CUSTOMER_ID_MISSING, "a customer account needs a customer number");
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Account account = Account.openCustomer(ids.next(), ibans.generate(accounts.nextAccountNumber()),
                command.customerId(), command.holder(), now);
        accounts.insert(account);
        audit.record(actor, OPEN, account.accountId(),
                Map.of("customerId", account.customerId().value(), "holderType", account.holder().type().name()),
                now);
        outbox.write(AccountEvents.TOPIC, account.accountId().toString(), AccountEvents.opened(account));
        return account;
    }

    /**
     * Adds a block of {@code reason} and publishes {@code AccountBlocked} (12 §3.2, UC-10). A reason already active is
     * a no-op: no event, no audit row, the revision stays. {@code SANCTIONS} lifts an active
     * {@code SANCTIONS_PROVISIONAL} in the same change.
     *
     * @param comment the caller's note, or {@code null}
     * @throws LedgerException {@code SANCTIONS_BLOCK_PROTECTED}, {@code FORBIDDEN}, {@code ACCOUNT_NOT_FOUND},
     *     {@code ACCOUNT_NOT_CUSTOMER}, {@code ACCOUNT_CLOSED}
     * @throws OptimisticConflict when the account changed since it was read: run the transaction again
     */
    @Transactional
    public BlockChange block(UUID accountId, BlockReason reason, String comment, Caller caller) {
        caller.requireMayChange(reason);
        BlockChange change = get(accountId).block(reason);
        if (!change.changed()) {
            return change;
        }
        Account blocked = change.account();
        Instant now = writeLifecycle(blocked);
        if (change.replaced() != null) {
            accounts.liftBlock(accountId, change.replaced(), now, caller.actor());
        }
        accounts.addBlock(accountId, reason, now, caller.actor(), comment);
        audit.record(caller.actor(), BLOCK, accountId, blockDetails(reason, change.replaced(), comment), now);
        outbox.write(AccountEvents.TOPIC, accountId.toString(), AccountEvents.blocked(blocked, reason, now));
        return change;
    }

    /**
     * Lifts the block of {@code reason} and publishes {@code AccountUnblocked} (12 §3.2, UC-10); the account stays
     * {@code BLOCKED} while other blocks remain. A reason that is not active is a no-op.
     *
     * @param comment the caller's note, or {@code null}; kept in the audit row
     * @throws LedgerException {@code SANCTIONS_BLOCK_PROTECTED}, {@code FORBIDDEN}, {@code ACCOUNT_NOT_FOUND},
     *     {@code ACCOUNT_NOT_CUSTOMER}, {@code ACCOUNT_CLOSED}
     * @throws OptimisticConflict when the account changed since it was read: run the transaction again
     */
    @Transactional
    public BlockChange unblock(UUID accountId, BlockReason reason, String comment, Caller caller) {
        caller.requireMayChange(reason);
        BlockChange change = get(accountId).unblock(reason);
        if (!change.changed()) {
            return change;
        }
        Account unblocked = change.account();
        Instant now = writeLifecycle(unblocked);
        accounts.liftBlock(accountId, reason, now, caller.actor());
        audit.record(caller.actor(), UNBLOCK, accountId, blockDetails(reason, null, comment), now);
        outbox.write(AccountEvents.TOPIC, accountId.toString(), AccountEvents.unblocked(unblocked, reason, now));
        return change;
    }

    /** All customer accounts of {@code customerId}, in any status (12 §3.2, for risk-service). */
    @Transactional(readOnly = true)
    public List<Account> byCustomer(CustomerId customerId) {
        return accounts.findByCustomerId(customerId);
    }

    /** The audit details of a block or unblock; {@code replaced} and {@code comment} only when present. */
    private static Map<String, Object> blockDetails(BlockReason reason, BlockReason replaced, String comment) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("reason", reason.name());
        if (replaced != null) {
            details.put("replaced", replaced.name());
        }
        if (comment != null) {
            details.put("comment", comment);
        }
        return details;
    }

    /** Writes the account's new status and revision version-checked (12 §6.2); returns the time of the change. */
    private Instant writeLifecycle(Account changed) {
        if (!accounts.updateLifecycle(changed)) {
            throw new OptimisticConflict("account " + changed.accountId() + " changed since it was read");
        }
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    /** @throws LedgerException {@code ACCOUNT_NOT_FOUND}, {@code ACCOUNT_NOT_CUSTOMER} */
    @Transactional(readOnly = true)
    public Account get(UUID accountId) {
        return customer(accounts.findById(accountId).orElseThrow(AccountService::notFound));
    }

    /** @throws LedgerException {@code ACCOUNT_NOT_FOUND}, {@code ACCOUNT_NOT_CUSTOMER} */
    @Transactional(readOnly = true)
    public Account getByIban(Iban iban) {
        return customer(accounts.findByIban(iban).orElseThrow(AccountService::notFound));
    }

    /**
     * A page of the account's postings, newest first (12 §3.2): at most {@link #ENTRIES_PAGE}. Pages are keyed on
     * {@code posting_id}; as {@code BIGSERIAL} ids can commit out of order (§6.3), a posting committed while a client
     * pages, with an id below its cursor, shows only on a fresh first page.
     *
     * @param from inclusive lower bound of the posting time, or {@code null}
     * @param to exclusive upper bound of the posting time, or {@code null}
     * @param beforePostingId where the previous page ended, {@code null} for the first
     * @throws LedgerException {@code ACCOUNT_NOT_FOUND}, {@code ACCOUNT_NOT_CUSTOMER}
     */
    @Transactional(readOnly = true)
    public PostingsPage entries(UUID accountId, Instant from, Instant to, Long beforePostingId) {
        get(accountId);
        List<AccountPosting> postings = journal.postings(accountId, from, to, beforePostingId, ENTRIES_PAGE + 1);
        if (postings.size() <= ENTRIES_PAGE) {
            return new PostingsPage(postings, null);
        }
        List<AccountPosting> page = postings.subList(0, ENTRIES_PAGE);
        return new PostingsPage(page, page.getLast().postingId());
    }

    /**
     * The Account API serves customer accounts: a technical account's balance is derived from postings and
     * snapshots (12 §6.3), not from its row, whose {@code booked_minor} stays 0.
     */
    private static Account customer(Account account) {
        if (account.kind() != AccountKind.CUSTOMER) {
            throw new LedgerException(LedgerError.ACCOUNT_NOT_CUSTOMER, "a technical account");
        }
        return account;
    }

    private static LedgerException notFound() {
        return new LedgerException(LedgerError.ACCOUNT_NOT_FOUND, "no such account");
    }
}
