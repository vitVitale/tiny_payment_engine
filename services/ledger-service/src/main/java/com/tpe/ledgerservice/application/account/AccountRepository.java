package com.tpe.ledgerservice.application.account;

import com.tpe.ledgerservice.domain.account.Account;
import com.tpe.ledgerservice.domain.account.BlockReason;
import com.tpe.ledgerservice.domain.account.CustomerId;
import com.tpe.paymentdomain.Iban;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code accounts} and {@code account_blocks} tables and {@code account_number_seq} (specs/12-ledger-service.md
 * §5). Accounts are read with their active block reasons.
 */
public interface AccountRepository {

    /** {@code nextval('account_number_seq')}: never reused, also after a rollback. */
    long nextAccountNumber();

    void insert(Account account);

    Optional<Account> findById(UUID accountId);

    Optional<Account> findByIban(Iban iban);

    /**
     * Reads the accounts with {@code SELECT … FOR UPDATE}, locking their rows in ascending {@code account_id} order
     * (12 §6.2), so two transactions locking the same accounts never deadlock on each other.
     *
     * @return the accounts found, in ascending {@code account_id} order
     */
    List<Account> lockForUpdate(Collection<UUID> accountIds);

    /** The customer accounts of a customer, in any status, oldest first. */
    List<Account> findByCustomerId(CustomerId customerId);

    /**
     * Writes a customer account's {@code booked_minor} and {@code held_minor} and bumps its version, if the row still
     * has {@code account.version()} (12 §6.2).
     *
     * @return {@code false} when another transaction changed the row since it was read
     */
    boolean updateBalances(Account account);

    /**
     * Writes a customer account's {@code status}, {@code revision} and {@code closed_at} and bumps its version, if the
     * row still has {@code account.version()} (12 §6.2): a concurrent status check either sees the change or
     * conflicts.
     *
     * @return {@code false} when another transaction changed the row since it was read
     */
    boolean updateLifecycle(Account account);

    /** Inserts an active block of {@code reason}. */
    void addBlock(UUID accountId, BlockReason reason, Instant at, String actor, String comment);

    /** Marks the active block of {@code reason} lifted. */
    void liftBlock(UUID accountId, BlockReason reason, Instant at, String actor);
}
