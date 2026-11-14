package com.tpe.paymentorchestrator.infrastructure.config;

import com.tpe.observability.context.Spans;
import com.tpe.observability.payment.PaymentTelemetry;
import com.tpe.messaging.worker.PollingWorker;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentorchestrator.application.ledger.Ledger;
import com.tpe.paymentorchestrator.application.outgoing.AssessPayeeRiskAction;
import com.tpe.paymentorchestrator.application.outgoing.AssessRiskAction;
import com.tpe.paymentorchestrator.application.outgoing.CreateHoldAction;
import com.tpe.paymentorchestrator.application.outgoing.SettleOnUsAction;
import com.tpe.paymentorchestrator.application.payments.Payments;
import com.tpe.paymentorchestrator.application.risk.RiskAssessments;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.SagaAction;
import com.tpe.paymentorchestrator.application.runner.SagaRunner;
import com.tpe.paymentorchestrator.application.runner.SagaTelemetry;
import com.tpe.paymentorchestrator.application.runner.StepApplier;
import com.tpe.paymentorchestrator.infrastructure.ledger.HttpLedger;
import com.tpe.paymentorchestrator.infrastructure.risk.HttpRiskAssessments;
import com.tpe.paymentorchestrator.infrastructure.telemetry.MicrometerSagaTelemetry;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.web.client.RestClient;

/**
 * The saga runner with its actions, the sweeper and the clients they call (specs/11-payment-orchestrator.md §6.2,
 * §6.3, §7).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SagaConfiguration.RunnerProperties.class, SagaConfiguration.RiskProperties.class,
        SagaConfiguration.LedgerProperties.class})
class SagaConfiguration {

    /** The sweeper's poll: actions whose lease expired or whose retry is due (§6.3). */
    private static final Duration SWEEP_INTERVAL = Duration.ofMillis(100);

    /** Every client's (specs/00-engineering-baseline.md §8). */
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(200);

    @Bean
    RunnerSettings runnerSettings(RunnerProperties runner) {
        return new RunnerSettings(runner.lease(), runner.maxBackoff(), runner.maxConcurrency(), runner.safetyMargin());
    }

    @Bean
    RiskAssessments riskAssessments(RestClient.Builder builder, RiskProperties risk) {
        return new HttpRiskAssessments(builder.clone().baseUrl(risk.baseUrl().toString()).build(), CONNECT_TIMEOUT);
    }

    @Bean
    AssessRiskAction assessRiskAction(RiskAssessments risk, RunnerSettings settings, RiskProperties properties,
            Clock clock) {
        return new AssessRiskAction(risk, settings, properties.timeout().outgoing(), clock);
    }

    @Bean
    Ledger ledger(RestClient.Builder builder, LedgerProperties ledger) {
        return new HttpLedger(builder.clone().baseUrl(ledger.baseUrl().toString()).build(), CONNECT_TIMEOUT);
    }

    @Bean
    CreateHoldAction createHoldAction(Ledger ledger, RunnerSettings settings, LedgerProperties properties,
            Clock clock) {
        return new CreateHoldAction(ledger, settings, properties.timeout(), clock);
    }

    @Bean
    AssessPayeeRiskAction assessPayeeRiskAction(RiskAssessments risk, RunnerSettings settings,
            RiskProperties properties, Clock clock) {
        return new AssessPayeeRiskAction(risk, settings, properties.timeout().outgoing(), clock);
    }

    @Bean
    SettleOnUsAction settleOnUsAction(Ledger ledger, RunnerSettings settings, LedgerProperties properties,
            Clock clock) {
        return new SettleOnUsAction(ledger, settings, properties.timeout(), clock);
    }

    @Bean
    SagaTelemetry sagaTelemetry(ObjectProvider<Spans> spans, PaymentTelemetry payments, MeterRegistry registry) {
        return new MicrometerSagaTelemetry(spans.getIfAvailable(Spans::noop), payments, registry);
    }

    @Bean
    SagaRunner sagaRunner(Payments payments, StepApplier applier, List<SagaAction> actions, RunnerSettings settings,
            Clock clock, SagaTelemetry telemetry) {
        return new SagaRunner(payments, applier, actions, settings, clock, telemetry);
    }

    /**
     * Hands every due action the runner performs to the runner after the poll's commit: an expired lease (the runner
     * crashed or its call hangs) or a scheduled retry. {@code FOR UPDATE SKIP LOCKED} spreads the rows over the
     * instances; the claim decides who runs each.
     */
    @Bean
    PollingWorker<UUID> sagaSweeper(SagaRunner runner, JdbcTemplate jdbc, PlatformTransactionManager transactions,
            Clock clock, MeterRegistry registry, ObjectProvider<Spans> spans) {
        // enum names, safe to inline
        String actions = runner.actions().stream().map(action -> "'" + action.name() + "'")
                .collect(Collectors.joining(", "));
        String due = "pending_action IS NOT NULL AND pending_action IN (" + actions + ") AND next_action_at <= ?";
        return new PollingWorker<>(new PollingWorker.Settings("saga-sweeper", 100, SWEEP_INTERVAL, 1), jdbc,
                transactions,
                "SELECT payment_id FROM payments WHERE " + due
                        + " ORDER BY next_action_at LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, n) -> rs.getObject(1, UUID.class),
                paymentId -> runner.submitAfterCommit(new PaymentId(paymentId), true),
                "SELECT min(next_action_at) FROM payments WHERE " + due, clock, registry,
                spans.getIfAvailable(Spans::noop));
    }

    /** {@code tpe.runner.*} (§7). */
    @ConfigurationProperties("tpe.runner")
    record RunnerProperties(Duration lease, Duration maxBackoff, int maxConcurrency, Duration safetyMargin) {}

    /** {@code tpe.clients.risk.*} (§7). */
    @ConfigurationProperties("tpe.clients.risk")
    record RiskProperties(URI baseUrl, Timeout timeout) {

        /**
         * @param outgoing {@code ASSESS_RISK} of an outgoing payment and {@code ASSESS_PAYEE_RISK} of an on-us one,
         *     500 ms (UC-04 2c)
         */
        record Timeout(Duration outgoing, Duration incoming) {}
    }

    /** {@code tpe.clients.ledger.*} (§7). */
    @ConfigurationProperties("tpe.clients.ledger")
    record LedgerProperties(URI baseUrl, Duration timeout) {}
}
