package com.tpe.ledgerservice.application.account;

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
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The account service of specs/12-ledger-service.md §2 (UC-10): opens and reads accounts. Every change commits
 * together with its {@code audit_log} row and its {@code accounts.events} message (ADR-002, ADR-007).
 */
@Service
public class AccountService {

    static final String OPEN = "OPEN";

    private final AccountRepository accounts;
    private final AuditLog audit;
    private final OutboxWriter outbox;
    private final IbanGenerator ibans;
    private final UuidV7Generator ids;
    private final Clock clock;

    public AccountService(AccountRepository accounts, AuditLog audit, OutboxWriter outbox, IbanGenerator ibans,
            UuidV7Generator ids, Clock clock) {
        this.accounts = accounts;
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
