package com.tpe.paymentorchestrator.application.outgoing;

import com.tpe.paymentorchestrator.application.ledger.Ledger;
import com.tpe.paymentorchestrator.application.ledger.LedgerAnswer;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** The ledger of the action tests: answers what the test set and records the read timeouts it was given. */
final class FakeLedger implements Ledger {

    final List<Duration> timeouts = new ArrayList<>();
    LedgerAnswer<HoldCreated> hold;
    LedgerAnswer<SettledOnUs> settlement;
    LedgerAnswer<Released> release;

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
    public LedgerAnswer<Released> releaseHold(Payment payment, Duration readTimeout) {
        timeouts.add(readTimeout);
        return release;
    }
}
