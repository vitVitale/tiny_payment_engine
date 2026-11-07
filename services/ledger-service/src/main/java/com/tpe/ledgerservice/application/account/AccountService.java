package com.tpe.ledgerservice.application.account;

import com.tpe.ledgerservice.application.posting.AccountPosting;
import com.tpe.ledgerservice.application.posting.JournalRepository;
import com.tpe.ledgerservice.application.posting.PostingsPage;
import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import com.tpe.ledgerservice.domain.account.Account;
import com.tpe.ledgerservice.domain.account.AccountKind;
import com.tpe.ledgerservice.domain.account.IbanGenerator;
import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.UuidV7Generator;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
