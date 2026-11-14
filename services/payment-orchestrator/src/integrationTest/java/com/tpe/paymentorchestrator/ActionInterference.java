package com.tpe.paymentorchestrator;

import com.tpe.paymentorchestrator.application.runner.SagaAction;
import com.tpe.paymentorchestrator.application.runner.StepOutcome;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PendingAction;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Stands between the saga runner and each of its actions in the component tests, after the action's remote call:
 * <ul>
 *   <li>{@link #crashAfter} — the next performance of the action throws once its call returned, as if the instance
 *       died there: the claim stays with its lease, nothing is applied, and a sweeper resumes it once the lease has
 *       expired (specs/11-payment-orchestrator.md §6.3, ADR-008);</li>
 *   <li>{@link #holdAfter} — every performance of the action waits after its call until {@link #releaseHeld}: the
 *       action is in flight for as long as the test needs, e.g. while a timer fires.</li>
 * </ul>
 * Each affects only the payments a test names, by their payee's IBAN: payments other tests left behind still retry
 * their actions meanwhile. Off unless a test sets it; {@link #reset} after each test.
 */
final class ActionInterference {

    private static final Duration MAX_HOLD = Duration.ofSeconds(20);

    private static volatile Predicate<Payment> affected = payment -> false;
    private static volatile PendingAction crashing;
    private static final AtomicBoolean CRASHED = new AtomicBoolean();
    private static volatile PendingAction holding;
    private static volatile CountDownLatch released = new CountDownLatch(0);
    private static final AtomicInteger HELD = new AtomicInteger();
    private static final AtomicInteger LEFT = new AtomicInteger();

    private ActionInterference() {}

    /** Crashes the next performance of {@code action} for the payment to {@code creditorIban}. */
    static void crashAfter(PendingAction action, String creditorIban) {
        CRASHED.set(false);
        affected = payment -> payment.creditor().iban().value().equals(creditorIban);
        crashing = action;
    }

    /** Holds every performance of {@code action} for the payment to {@code creditorIban}. */
    static void holdAfter(PendingAction action, String creditorIban) {
        released = new CountDownLatch(1);
        HELD.set(0);
        LEFT.set(0);
        affected = payment -> payment.creditor().iban().value().equals(creditorIban);
        holding = action;
    }

    /** How many performances of the held action wait. */
    static int held() {
        return HELD.get() - LEFT.get();
    }

    /** How many performances of the held action went on after {@link #releaseHeld}. */
    static int left() {
        return LEFT.get();
    }

    static void releaseHeld() {
        released.countDown();
    }

    static void reset() {
        affected = payment -> false;
        crashing = null;
        holding = null;
        released.countDown();
    }

    /** The crash of {@link #crashAfter}: the runner logs it and leaves the claim to the sweeper. */
    static final class SimulatedCrash extends RuntimeException {

        SimulatedCrash(PendingAction action) {
            super("simulated crash after the remote call of " + action, null, false, false);
        }
    }

    /** Wraps every {@link SagaAction} bean. */
    static BeanPostProcessor wrapper() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessAfterInitialization(Object bean, String beanName) {
                return bean instanceof SagaAction action ? new Interfering(action) : bean;
            }
        };
    }

    private record Interfering(SagaAction delegate) implements SagaAction {

        @Override
        public PendingAction action() {
            return delegate.action();
        }

        @Override
        public StepOutcome perform(Payment payment) {
            StepOutcome outcome = delegate.perform(payment);
            if (!affected.test(payment)) {
                return outcome;
            }
            if (crashing == action() && CRASHED.compareAndSet(false, true)) {
                throw new SimulatedCrash(action());
            }
            if (holding == action()) {
                HELD.incrementAndGet();
                try {
                    released.await(MAX_HOLD.toMillis(), TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    LEFT.incrementAndGet();
                }
            }
            return outcome;
        }

        @Override
        public Optional<StepOutcome.Moved> giveUp(Payment payment, int attempts, StepOutcome.TechnicalFailure failure) {
            return delegate.giveUp(payment, attempts, failure);
        }

        @Override
        public boolean opensStuckCase() {
            return delegate.opensStuckCase();
        }
    }
}
