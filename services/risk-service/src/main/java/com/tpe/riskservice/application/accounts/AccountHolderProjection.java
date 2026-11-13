package com.tpe.riskservice.application.accounts;

import com.tpe.messaging.inbox.InboxGuard;
import com.tpe.riskservice.domain.accounts.AccountHolder;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps {@code account_holders} in step with {@code accounts.events} (specs/14-risk-service.md §4, ADR-007), so the
 * rules never call ledger-service. Each message is handled once ({@link InboxGuard}, dedup key the {@code eventId}); a
 * snapshot replaces the row only when its revision is newer, a tombstone deletes it.
 */
@Service
public class AccountHolderProjection {

    /** The topic, also the inbox's {@code topic}. */
    public static final String TOPIC = "accounts.events";

    private final AccountHolders holders;
    private final InboxGuard inbox;
    private final Clock clock;

    public AccountHolderProjection(AccountHolders holders, InboxGuard inbox, Clock clock) {
        this.holders = holders;
        this.inbox = inbox;
        this.clock = clock;
    }

    /** The snapshot of message {@code eventId}; a replayed older revision changes nothing. */
    @Transactional
    public void apply(UUID eventId, AccountHolder holder) {
        if (firstTime(eventId)) {
            holders.upsertIfNewer(holder, clock.instant());
        }
    }

    /** The tombstone of message {@code eventId}: the account's key left the topic 13 months after its close. */
    @Transactional
    public void remove(UUID eventId, UUID accountId) {
        if (firstTime(eventId)) {
            holders.delete(accountId);
        }
    }

    /** Records the message in the inbox, keyed by its {@code eventId}; {@code false} if it was handled before. */
    private boolean firstTime(UUID eventId) {
        return inbox.firstTime(eventId.toString(), TOPIC, eventId);
    }
}
