package com.tpe.paymentorchestrator.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.tpe.paymentdomain.BankDirectory;
import com.tpe.paymentorchestrator.application.payments.Payments;
import com.tpe.paymentorchestrator.application.runner.SagaRunner;
import com.tpe.paymentorchestrator.domain.payment.OutgoingTimings;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** The startup check of specs/11-payment-orchestrator.md §7: the application does not start with broken offsets. */
class OutgoingTimingsStartupTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(OrchestratorConfiguration.class)
            .withBean(Payments.class, () -> mock(Payments.class))
            .withBean(BankDirectory.class, () -> mock(BankDirectory.class))
            .withBean(SagaRunner.class, () -> mock(SagaRunner.class));

    @Test
    void theDefaultsStart() {
        context.withPropertyValues("tpe.outgoing.send-by-offset=10s", "tpe.outgoing.deadline-offset=20s",
                        "tpe.outgoing.hold-give-up-margin=2s")
                .run(started -> assertThat(started.getBean(OutgoingTimings.class))
                        .isEqualTo(new OutgoingTimings(Duration.ofSeconds(10), Duration.ofSeconds(20),
                                Duration.ofSeconds(2))));
    }

    @Test
    void aSendByOffsetAtTheDeadlineFailsTheStartup() {
        context.withPropertyValues("tpe.outgoing.send-by-offset=20s", "tpe.outgoing.deadline-offset=20s",
                        "tpe.outgoing.hold-give-up-margin=2s")
                .run(started -> assertThat(started).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("send-by-offset (PT20S) < deadline-offset (PT20S)"));
    }

    @Test
    void aHoldGiveUpMarginBeyondTheSendByOffsetFailsTheStartup() {
        context.withPropertyValues("tpe.outgoing.send-by-offset=10s", "tpe.outgoing.deadline-offset=20s",
                        "tpe.outgoing.hold-give-up-margin=12s")
                .run(started -> assertThat(started).hasFailed().getFailure()
                        .rootCause().hasMessageContaining("hold-give-up-margin (PT12S) < send-by-offset (PT10S)"));
    }
}
