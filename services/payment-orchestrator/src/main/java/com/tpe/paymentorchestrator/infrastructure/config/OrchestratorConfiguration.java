package com.tpe.paymentorchestrator.infrastructure.config;

import com.tpe.contracts.ContractsModule;
import com.tpe.observability.context.Spans;
import com.tpe.paymentdomain.BankDirectory;
import com.tpe.paymentdomain.UlidGenerator;
import com.tpe.paymentdomain.UuidV7Generator;
import com.tpe.paymentorchestrator.application.outgoing.PaymentSubmission;
import com.tpe.paymentorchestrator.application.payments.Payments;
import com.tpe.paymentorchestrator.domain.payment.OutgoingTimings;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time, ids, JSON of the internal API and the outgoing time limits (specs/00-engineering-baseline.md §4, §6,
 * specs/11-payment-orchestrator.md §3.1, §7). The application does not start with limits that break
 * {@code hold-give-up-margin < send-by-offset < deadline-offset}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OrchestratorConfiguration.OutgoingProperties.class)
class OrchestratorConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** The value objects of the {@code contracts} DTOs as strings (01 §4); Spring Boot registers it with Jackson. */
    @Bean
    ContractsModule contractsModule() {
        return new ContractsModule();
    }

    /** {@code paymentId}: UUID v7. */
    @Bean
    UuidV7Generator paymentIds(Clock clock) {
        return new UuidV7Generator(clock, new SecureRandom());
    }

    /** The ULID of an outgoing {@code TxId}. */
    @Bean
    UlidGenerator ulids(Clock clock) {
        return new UlidGenerator(clock, new SecureRandom());
    }

    @Bean
    OutgoingTimings outgoingTimings(OutgoingProperties outgoing) {
        return new OutgoingTimings(outgoing.sendByOffset(), outgoing.deadlineOffset(), outgoing.holdGiveUpMargin());
    }

    @Bean
    PaymentSubmission paymentSubmission(Payments payments, BankDirectory directory, OutgoingTimings timings,
            Clock clock, UuidV7Generator paymentIds, UlidGenerator ulids, ObjectProvider<Spans> spans) {
        return new PaymentSubmission(payments, directory, timings, clock, paymentIds, ulids,
                spans.getIfAvailable(Spans::noop));
    }

    /** {@code tpe.outgoing.*} (§7). */
    @ConfigurationProperties("tpe.outgoing")
    record OutgoingProperties(Duration sendByOffset, Duration deadlineOffset, Duration holdGiveUpMargin) {}
}
