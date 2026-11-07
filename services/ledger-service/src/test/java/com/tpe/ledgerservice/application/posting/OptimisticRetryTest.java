package com.tpe.ledgerservice.application.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** specs/12-ledger-service.md §6.2: up to 5 attempts with 5–20 ms jitter, then 409 CONCURRENT_MODIFICATION. */
class OptimisticRetryTest {

    private final List<Long> pauses = new ArrayList<>();
    private final OptimisticRetry retry = new OptimisticRetry(5, pauses::add);

    @Test
    void aTransactionWithoutConflictRunsOnceAndDoesNotWait() {
        AtomicInteger runs = new AtomicInteger();

        String result = retry.run(() -> {
            runs.incrementAndGet();
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(runs).hasValue(1);
        assertThat(pauses).isEmpty();
    }

    @Test
    void aConflictRunsTheTransactionAgainAfterJitterOf5To20Millis() {
        AtomicInteger runs = new AtomicInteger();

        String result = retry.run(() -> {
            if (runs.incrementAndGet() < 5) {
                throw new OptimisticConflict("lost");
            }
            return "done";
        });

        assertThat(result).isEqualTo("done");
        assertThat(runs).hasValue(5);
        assertThat(pauses).hasSize(4).allSatisfy(pause -> assertThat(pause).isBetween(5L, 20L));
    }

    @Test
    void afterTheLastAttemptConflictsItIsConcurrentModification() {
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> retry.run(() -> {
            runs.incrementAndGet();
            throw new OptimisticConflict("lost");
        })).isInstanceOfSatisfying(LedgerException.class,
                e -> assertThat(e.error()).isEqualTo(LedgerError.CONCURRENT_MODIFICATION));
        assertThat(runs).hasValue(5);
        assertThat(pauses).hasSize(4);
    }

    @Test
    void otherFailuresAreNotRetried() {
        AtomicInteger runs = new AtomicInteger();

        assertThatThrownBy(() -> retry.run(() -> {
            runs.incrementAndGet();
            throw new LedgerException(LedgerError.INSUFFICIENT_FUNDS, "no");
        })).isInstanceOf(LedgerException.class);
        assertThat(runs).hasValue(1);
    }

    @Test
    void atLeastOneAttempt() {
        assertThatIllegalArgumentException().isThrownBy(() -> new OptimisticRetry(0, millis -> {}));
    }
}
