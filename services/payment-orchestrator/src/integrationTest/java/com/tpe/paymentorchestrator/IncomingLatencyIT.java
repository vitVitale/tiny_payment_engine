package com.tpe.paymentorchestrator;

import static com.tpe.paymentorchestrator.IncomingPaymentIT.creditTransferReceived;
import static com.tpe.paymentorchestrator.IncomingPaymentIT.txId;
import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.Bic;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * specs/11-payment-orchestrator.md §8: {@code CreditTransferReceived} consumed → verdict in the outbox ≤ 1.2 s p99, in
 * the 2 s budget of UC-06. Measured from the moment the event is on the topic (the publish returned) to its
 * {@code AcceptCreditTransfer} in the outbox, with risk-service and ledger-service answering at once.
 */
class IncomingLatencyIT extends OrchestratorStack {

    private static final int TRANSFERS = 100;
    private static final Duration P99 = Duration.ofMillis(1200);
    private static final Duration AT_MOST = Duration.ofSeconds(15);

    @Autowired
    JdbcClient jdbc;

    private SagaProbe probe;

    @BeforeEach
    void probe() {
        probe = new SagaProbe(jdbc, CLOCK, RISK, LEDGER);
        riskAnswers("allow");
        ledgerAnswers("create-pending-credit-created", "cancel-pending-credit-cancelled");
    }

    @Test
    void aReceiptIsAnsweredWithAVerdictInTheOutboxWithin1200msP99() throws InterruptedException {
        long[] latencies = new long[TRANSFERS];
        for (int i = 0; i < TRANSFERS; i++) {
            String txId = txId();
            publishCsmEvent(creditTransferReceived(new Bic("PEERDEFFXXX"), txId, Ibans.own()), txId,
                    UUID.randomUUID());
            long started = System.nanoTime();
            while (probe.csmCommands(txId).isEmpty()) {
                assertThat(System.nanoTime() - started).as("a verdict for %s within %s", txId, AT_MOST)
                        .isLessThan(AT_MOST.toNanos());
                Thread.sleep(2);
            }
            latencies[i] = System.nanoTime() - started;
        }

        long[] sorted = latencies.clone();
        Arrays.sort(sorted);
        long p99 = sorted[(int) Math.ceil(sorted.length * 0.99) - 1];
        assertThat(Duration.ofNanos(p99)).as("p99 of %d receipts → verdict (max %d ms)", TRANSFERS,
                TimeUnit.NANOSECONDS.toMillis(sorted[sorted.length - 1])).isLessThanOrEqualTo(P99);
    }
}
