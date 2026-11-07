package com.tpe.ledgerservice.application.posting;

import com.tpe.contracts.TechnicalAccounts;
import com.tpe.ledgerservice.application.account.AccountRepository;
import com.tpe.ledgerservice.application.account.AuditLog;
import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import com.tpe.ledgerservice.domain.account.Account;
import com.tpe.ledgerservice.domain.account.AccountKind;
import com.tpe.ledgerservice.domain.posting.JournalEntry;
import com.tpe.ledgerservice.domain.posting.PostingEngine;
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
 * Test funding (specs/12-ledger-service.md §3.2, UC-10 3): a journal entry Dr FUNDING / Cr customer through the
 * posting engine. The customer row is read, changed in memory and written version-checked (§6.2); the FUNDING row is
 * only read, never written (ADR-005). Entry, balance and audit row commit together (LED-AC-18).
 */
@Service
public class FundingService {

    static final String FUND = "FUND";

    private final AccountRepository accounts;
    private final JournalRepository journal;
    private final AuditLog audit;
    private final UuidV7Generator ids;
    private final Clock clock;

    public FundingService(AccountRepository accounts, JournalRepository journal, AuditLog audit, UuidV7Generator ids,
            Clock clock) {
        this.accounts = accounts;
        this.journal = journal;
        this.audit = audit;
        this.ids = ids;
        this.clock = clock;
    }

    /**
     * @param amountMinor positive; the API validates it
     * @param actor the JWT {@code sub} of the operator
     * @throws LedgerException {@code CURRENCY_NOT_SUPPORTED}, {@code ACCOUNT_NOT_FOUND}, {@code ACCOUNT_NOT_CUSTOMER},
     *     {@code CUSTOMER_ID_MISSING}
     * @throws OptimisticConflict when the account changed since it was read: run the transaction again
     */
    @Transactional
    public Funded fund(UUID accountId, long amountMinor, String currency, String actor) {
        if (!Account.EUR.equals(currency)) {
            throw new LedgerException(LedgerError.CURRENCY_NOT_SUPPORTED, "accounts are held in EUR only");
        }
        Account customer = accounts.findById(accountId)
                .orElseThrow(() -> new LedgerException(LedgerError.ACCOUNT_NOT_FOUND, "no such account"));
        if (customer.kind() != AccountKind.CUSTOMER) {
            throw new LedgerException(LedgerError.ACCOUNT_NOT_CUSTOMER, "a technical account");
        }
        if (customer.customerId() == null) {
            throw new LedgerException(LedgerError.CUSTOMER_ID_MISSING, "the account has no customer number");
        }
        Account funding = accounts.findById(TechnicalAccounts.FUNDING_ID)
                .orElseThrow(() -> new IllegalStateException("the FUNDING technical account is missing"));
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        JournalEntry entry = JournalEntry.funding(ids.next(), accountId, amountMinor, now);

        List<Account> changed = PostingEngine.post(entry, List.of(customer, funding));
        for (Account account : changed) {
            if (!accounts.updateBalances(account)) {
                throw new OptimisticConflict("account " + account.accountId() + " changed since it was read");
            }
        }
        Account funded = changed.stream().filter(account -> account.accountId().equals(accountId)).findFirst()
                .orElseThrow();
        journal.insert(entry);
        audit.record(actor, FUND, accountId,
                Map.of("amountMinor", amountMinor, "currency", currency, "journalEntryId", entry.entryId()), now);
        return new Funded(entry, funded, amountMinor);
    }
}
