package com.tpe.paymentorchestrator.application.ledger;

import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentorchestrator.domain.payment.RejectionCause;
import java.util.Map;
import java.util.Objects;

/**
 * A business refusal of ledger-service: its {@code code} (specs/12-ledger-service.md §3.4) and what the payment is
 * rejected with (specs/11-payment-orchestrator.md §6.2). An account refusal is the customer's business; anything else,
 * {@code CUSTOMER_ID_MISSING} included (Q-02), is a technical {@code MS03}.
 */
public record LedgerRefusal(String code) {

    /** A settled hold cannot be released: two contradicting decisions were made (ADR-009). */
    public static final String HOLD_SETTLED = "HOLD_SETTLED";

    /** A caller bug: the key was reused for another request, or the hold settled to another payee. */
    public static final String IDEMPOTENCY_CONFLICT = "IDEMPOTENCY_CONFLICT";

    private static final Map<String, ReasonCode> ACCOUNT_REFUSALS = Map.of(
            "ACCOUNT_NOT_FOUND", ReasonCode.AC01,
            "ACCOUNT_CLOSED", ReasonCode.AC04,
            "ACCOUNT_BLOCKED", ReasonCode.AC06,
            "ACCOUNT_NOT_CUSTOMER", ReasonCode.AG01,
            "INSUFFICIENT_FUNDS", ReasonCode.AM04);

    public LedgerRefusal {
        Objects.requireNonNull(code, "code");
    }

    public ReasonCode reason() {
        return ACCOUNT_REFUSALS.getOrDefault(code, ReasonCode.MS03);
    }

    /** {@code tpe.payment.rejected} {@code cause}: {@code BUSINESS} for an account refusal, else {@code TECHNICAL}. */
    public RejectionCause cause() {
        return ACCOUNT_REFUSALS.containsKey(code) ? RejectionCause.BUSINESS : RejectionCause.TECHNICAL;
    }
}
