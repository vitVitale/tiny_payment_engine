package com.tpe.paymentorchestrator.application.outgoing;

import com.tpe.contracts.Party;
import com.tpe.contracts.SendCreditTransfer;
import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentorchestrator.application.runner.SagaAction;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentParty;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import com.tpe.paymentorchestrator.domain.payment.RejectionCause;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code SEND} (specs/11-payment-orchestrator.md §6.2, UC-04 5, 5a), local: before {@code send_by} the step's
 * transaction writes {@code SendCreditTransfer} to the outbox and the payment is {@code SENT_TO_CSM}; at or after it
 * nothing is sent, the payment is {@code REJECTED AB03} and its hold is released. The command carries the payment's
 * trace (ADR-014), current while the runner performs and applies the step.
 */
public class SendAction implements SagaAction {

    private final Clock clock;

    public SendAction(Clock clock) {
        this.clock = clock;
    }

    @Override
    public PendingAction action() {
        return PendingAction.SEND;
    }

    @Override
    public StepOutcome perform(Payment payment) {
        Instant now = clock.instant();
        if (!now.isBefore(payment.sendBy())) {
            return new StepOutcome.Moved(payment.rejected(ReasonCode.AB03, PendingAction.RELEASE_HOLD, now),
                    "SEND_BY_PASSED", RejectionCause.TECHNICAL);
        }
        return new StepOutcome.Moved(payment.sent(), "SENT", null, null, command(payment));
    }

    /** A local step never fails. */
    @Override
    public Optional<StepOutcome.Moved> giveUp(Payment payment, int attempts, StepOutcome.TechnicalFailure failure) {
        return Optional.empty();
    }

    private static SendCreditTransfer command(Payment payment) {
        return new SendCreditTransfer(payment.paymentId(), payment.txId(), payment.uetr(), payment.endToEndId(),
                payment.acceptedAt(), payment.sendBy(), payment.deadlineAt(), payment.amount(),
                party(payment.debtor(), payment.debtorAccountId()), payment.debtor().agentBic(),
                party(payment.creditor(), null), payment.creditor().agentBic(), payment.remittance());
    }

    private static Party party(PaymentParty party, UUID accountId) {
        return new Party(party.name(), party.iban(), accountId);
    }
}
