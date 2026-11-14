package com.tpe.paymentorchestrator.application.ledger;

import com.tpe.paymentorchestrator.domain.payment.Payment;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * ledger-service's Posting API for an outgoing payment (specs/12-ledger-service.md §3.1). The hold id is the
 * payment's {@code paymentId} and every call carries the {@code Idempotency-Key} the ledger expects
 * ({@code hold-create:<holdId>}, {@code hold-settle-on-us:<holdId>}), so asking again after a lost answer makes no
 * second posting.
 */
public interface Ledger {

    /** {@code POST /v1/holds}: reserves the amount on the payer's account. Never throws. */
    LedgerAnswer<HoldCreated> createHold(Payment payment, Duration readTimeout);

    /** {@code POST /v1/holds/{holdId}/settle-on-us}: one entry Dr payer / Cr payee. Never throws. */
    LedgerAnswer<SettledOnUs> settleOnUs(Payment payment, Duration readTimeout);

    /** @param accountId the payer's account */
    record HoldCreated(UUID accountId) {

        public HoldCreated {
            Objects.requireNonNull(accountId, "accountId");
        }
    }

    /**
     * @param journalEntryId the entry that moved the money, {@code bookingEntryId} of {@code PaymentSettled}
     * @param creditorAccountId the payee's account
     */
    record SettledOnUs(UUID journalEntryId, UUID creditorAccountId) {

        public SettledOnUs {
            Objects.requireNonNull(journalEntryId, "journalEntryId");
            Objects.requireNonNull(creditorAccountId, "creditorAccountId");
        }
    }
}
