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

    Optional<Account> findByIban(Iban iban);
}
