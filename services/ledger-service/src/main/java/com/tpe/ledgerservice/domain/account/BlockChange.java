package com.tpe.ledgerservice.domain.account;

/**
 * The outcome of a block or unblock (specs/12-ledger-service.md §3.2).
 *
 * @param account the account after it; unchanged when {@code changed} is false
 * @param changed false for a no-op: the reason was already active (block) or not active (unblock)
 * @param replaced the {@code SANCTIONS_PROVISIONAL} block a {@code SANCTIONS} block lifted, or {@code null}
 */
public record BlockChange(Account account, boolean changed, BlockReason replaced) {}
