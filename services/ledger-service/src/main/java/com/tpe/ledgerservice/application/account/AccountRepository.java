package com.tpe.ledgerservice.application.account;

import com.tpe.ledgerservice.domain.account.Account;
import com.tpe.paymentdomain.Iban;
import java.util.Optional;
import java.util.UUID;

/** The {@code accounts} table and {@code account_number_seq} (specs/12-ledger-service.md §5). */
public interface AccountRepository {

    /** {@code nextval('account_number_seq')}: never reused, also after a rollback. */
    long nextAccountNumber();

    void insert(Account account);

    Optional<Account> findById(UUID accountId);

    /**
     * Writes a customer account's {@code booked_minor} and {@code held_minor} and bumps its version, if the row still
     * has {@code account.version()} (12 §6.2).
     *
     * @return {@code false} when another transaction changed the row since it was read
     */
    boolean updateBalances(Account account);

    Optional<Account> findByIban(Iban iban);
}
