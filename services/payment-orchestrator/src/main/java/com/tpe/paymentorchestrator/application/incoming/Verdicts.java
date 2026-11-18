package com.tpe.paymentorchestrator.application.incoming;

import com.tpe.contracts.AcceptCreditTransfer;
import com.tpe.contracts.RejectCreditTransfer;
import com.tpe.paymentdomain.StatusReason;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import java.time.Instant;
import java.util.UUID;

/**
 * The verdicts of an incoming payment (specs/11-payment-orchestrator.md §6.2, specs/02-messaging-contracts.md §3.3):
 * the move and the command for csm-gateway its transaction writes to the outbox, decided at {@code at}.
 */
final class Verdicts {

    private Verdicts() {}

    /** {@code ACCEPT_REQUESTED} with {@code AcceptCreditTransfer}: the pending credit on the payee's account exists. */
    static StepOutcome.Moved accept(Payment payment, UUID creditorAccountId, String cause, Instant at) {
        Payment next = payment.acceptRequested(creditorAccountId);
        return new StepOutcome.Moved(next, cause, null, null,
                new AcceptCreditTransfer(next.paymentId(), next.txId(), next.debtor().agentBic(), at));
    }

    /**
     * The verdict {@code REJECT} with {@code RejectCreditTransfer}: the payment stays {@code RECEIVED} until the
     * gateway's answer (ADR-003), so no rejection is counted yet.
     */
    static StepOutcome.Moved reject(Payment payment, StatusReason reason, String cause, Instant at) {
        Payment next = payment.verdictRejected(reason);
        return new StepOutcome.Moved(next, cause, null, null,
                new RejectCreditTransfer(next.paymentId(), next.txId(), next.debtor().agentBic(), at, reason));
    }
}
