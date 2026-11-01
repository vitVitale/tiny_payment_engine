package com.tpe.ledgerservice.application.account;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * {@code audit_log} (specs/12-ledger-service.md §5, §8): one row per successful admin action, written in the
 * transaction of the change (LED-AC-18).
 */
public interface AuditLog {

    /**
     * @param actor JWT {@code sub} of the staff member, or {@code risk-service} for internal calls
     * @param details what the action was about, stored as JSON
     */
    void record(String actor, String action, UUID accountId, Map<String, ?> details, Instant at);
}
