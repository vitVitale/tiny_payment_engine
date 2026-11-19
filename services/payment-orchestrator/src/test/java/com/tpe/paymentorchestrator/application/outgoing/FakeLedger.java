package com.tpe.paymentorchestrator.application.outgoing;

import com.tpe.paymentorchestrator.application.ledger.Ledger;
import com.tpe.paymentorchestrator.application.ledger.LedgerAnswer;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** The ledger of the action tests: answers what the test set and records the read timeouts it was given. */
public final class FakeLedger implements Ledger {

    public final List<Duration> timeouts = new ArrayList<>();
    public LedgerAnswer<HoldCreated> hold;
    public LedgerAnswer<SettledOnUs> settlement;
    public LedgerAnswer<Released> release;
    public LedgerAnswer<HoldSettled> holdSettlement;
    public LedgerAnswer<PendingCreditCreated> pendingCredit;
    public LedgerAnswer<Cancelled> cancel;
    public LedgerAnswer<CreditConfirmed> confirm;

    @Override
    public LedgerAnswer<HoldCreated> createHold(Payment payment, Duration readTimeout) {
        timeouts.add(readTimeout);
        return hold;
    }

    @Override
    public LedgerAnswer<SettledOnUs> settleOnUs(Payment payment, Duration readTimeout) {
        timeouts.add(readTimeout);
        return settlement;
    }

    @Override
    public LedgerAnswer<HoldSettled> settleHold(Payment payment, Duration readTimeout) {
        timeouts.add(readTimeout);
        return holdSettlement;
    }

    @Override
    public LedgerAnswer<Released> releaseHold(Payment payment, Duration readTimeout) {
        timeouts.add(readTimeout);
        return release;
    }

    @Override
    public LedgerAnswer<PendingCreditCreated> createPendingCredit(Payment payment, Duration readTimeout) {
        timeouts.add(readTimeout);
        return pendingCredit;
    }

    @Override
    public LedgerAnswer<CreditConfirmed> confirmPendingCredit(Payment payment, Duration readTimeout) {
        timeouts.add(readTimeout);
        return confirm;
    }

    @Override
    public LedgerAnswer<Cancelled> cancelPendingCredit(Payment payment, Duration readTimeout) {
        timeouts.add(readTimeout);
        return cancel;
    }
}
