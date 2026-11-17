package com.tpe.paymentorchestrator.application.timers;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** What the timer handlers share. */
final class Timers {

    private Timers() {}

    /** Runs {@code action} once the current transaction commits. */
    static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
