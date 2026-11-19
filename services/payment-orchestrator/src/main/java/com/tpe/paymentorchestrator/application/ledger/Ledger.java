package com.tpe.paymentorchestrator.application.ledger;

import com.tpe.paymentorchestrator.domain.payment.Payment;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * ledger-service's Posting API (specs/12-ledger-service.md §3.1): holds for an outgoing payment, pending credits for
 * an incoming one. The hold id and the pending credit id are the payment's {@code paymentId} (ADR-010), and every
 * call carries the {@code Idempotency-Key} the ledger expects ({@code hold-create:<holdId>},
 * {@code hold-settle-on-us:<holdId>}, {@code hold-release:<holdId>}, {@code pending-credit-create:<id>},
 * {@code pending-credit-confirm:<id>}, {@code pending-credit-cancel:<id>}), so asking again after a lost answer makes no second posting.
 */
public interface Ledger {

    /** {@code POST /v1/holds}: reserves the amount on the payer's account. Never throws. */
    LedgerAnswer<HoldCreated> createHold(Payment payment, Duration readTimeout);

    /** {@code POST /v1/holds/{holdId}/settle-on-us}: one entry Dr payer / Cr payee. Never throws. */
    LedgerAnswer<SettledOnUs> settleOnUs(Payment payment, Duration readTimeout);

    /**
     * {@code POST /v1/holds/{holdId}/settle}: one entry Dr payer / Cr the CSM settlement account, after the CSM settled
     * the payment. Never throws.
     */
    LedgerAnswer<HoldSettled> settleHold(Payment payment, Duration readTimeout);

    /**
     * {@code POST /v1/holds/{holdId}/release}: frees the payer's funds; for a hold that never came, a tombstone that
     * refuses a late create (ADR-009). Never throws.
     */
    LedgerAnswer<Released> releaseHold(Payment payment, Duration readTimeout);

    /**
     * {@code POST /v1/pending-credits}: records the incoming amount on the payee's account, changing no balance.
     * Never throws.
     */
    LedgerAnswer<PendingCreditCreated> createPendingCredit(Payment payment, Duration readTimeout);

    /**
     * {@code POST /v1/pending-credits/{id}/confirm}: one entry Dr the CSM settlement account / Cr payee, after the CSM
     * settled the payment, whatever the payee account's status. Never throws.
     */
    LedgerAnswer<CreditConfirmed> confirmPendingCredit(Payment payment, Duration readTimeout);

    /**
     * {@code POST /v1/pending-credits/{id}/cancel}: removes the pending credit; for one that never came, a tombstone
     * that refuses a late create (ADR-009). Never throws.
     */
    LedgerAnswer<Cancelled> cancelPendingCredit(Payment payment, Duration readTimeout);

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

    /** @param journalEntryId the entry that moved the money, {@code bookingEntryId} of {@code PaymentSettled} */
    record HoldSettled(UUID journalEntryId) {

        public HoldSettled {
            Objects.requireNonNull(journalEntryId, "journalEntryId");
        }
    }

    /** The hold is {@code RELEASED}, a tombstone included. */
    record Released() {}

    /** @param accountId the payee's account */
    record PendingCreditCreated(UUID accountId) {

        public PendingCreditCreated {
            Objects.requireNonNull(accountId, "accountId");
        }
    }

    /**
     * @param journalEntryId the entry that booked the money, {@code bookingEntryId} of {@code PaymentSettled}
     * @param accountId the payee's account
     */
    record CreditConfirmed(UUID journalEntryId, UUID accountId) {

        public CreditConfirmed {
            Objects.requireNonNull(journalEntryId, "journalEntryId");
            Objects.requireNonNull(accountId, "accountId");
        }
    }

    /** The pending credit is {@code CANCELLED}, a tombstone included. */
    record Cancelled() {}
}
