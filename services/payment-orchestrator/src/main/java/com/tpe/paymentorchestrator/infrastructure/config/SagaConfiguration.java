package com.tpe.paymentorchestrator.infrastructure.config;

import com.tpe.messaging.inbox.InboxGuard;
import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.messaging.worker.PollingWorker;
import com.tpe.observability.context.Spans;
import com.tpe.observability.payment.PaymentTelemetry;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.UuidV7Generator;
import com.tpe.paymentorchestrator.application.cases.CaseResolutions;
import com.tpe.paymentorchestrator.application.cases.ExceptionCases;
import com.tpe.paymentorchestrator.application.csm.CsmEventHandling;
import com.tpe.paymentorchestrator.application.ledger.Ledger;
import com.tpe.paymentorchestrator.application.incoming.AssessIncomingRiskAction;
import com.tpe.paymentorchestrator.application.incoming.CancelCreditAction;
import com.tpe.paymentorchestrator.application.incoming.CreatePendingCreditAction;
import com.tpe.paymentorchestrator.application.incoming.IncomingCsmEvents;
import com.tpe.paymentorchestrator.application.outgoing.AssessPayeeRiskAction;
import com.tpe.paymentorchestrator.application.outgoing.AssessRiskAction;
import com.tpe.paymentorchestrator.application.outgoing.CreateHoldAction;
import com.tpe.paymentorchestrator.application.outgoing.ReleaseHoldAction;
import com.tpe.paymentorchestrator.application.outgoing.SendAction;
import com.tpe.paymentorchestrator.application.outgoing.SettleHoldAction;
import com.tpe.paymentorchestrator.application.outgoing.SettleOnUsAction;
import com.tpe.paymentorchestrator.application.payments.DueTimer;
import com.tpe.paymentorchestrator.application.payments.FinalEvents;
import com.tpe.paymentorchestrator.application.payments.Payments;
import com.tpe.paymentorchestrator.application.risk.RiskAssessments;
import com.tpe.paymentorchestrator.application.runner.ByDirection;
import com.tpe.paymentorchestrator.application.runner.RunnerSettings;
import com.tpe.paymentorchestrator.application.runner.SagaAction;
import com.tpe.paymentorchestrator.application.runner.SagaRunner;
import com.tpe.paymentorchestrator.application.runner.SagaTelemetry;
import com.tpe.paymentorchestrator.application.runner.StepApplier;
import com.tpe.paymentorchestrator.application.timers.InvestigationTimer;
import com.tpe.paymentorchestrator.application.timers.OutgoingDeadlineTimer;
import com.tpe.paymentorchestrator.domain.payment.InvestigationPolicy;
import com.tpe.paymentorchestrator.domain.payment.OutgoingTimings;
import com.tpe.paymentorchestrator.domain.payment.TimerType;
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
 * The saga runner with its actions, the sweeper, the timers and the clients they call
 * (specs/11-payment-orchestrator.md §6.2, §6.3, §6.4, §7).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SagaConfiguration.RunnerProperties.class, SagaConfiguration.RiskProperties.class,
        SagaConfiguration.LedgerProperties.class, SagaConfiguration.InvestigationProperties.class,
        SagaConfiguration.IncomingProperties.class})
class SagaConfiguration {

    /** The sweeper's poll: actions whose lease expired or whose retry is due (§6.3). */
    private static final Duration SWEEP_INTERVAL = Duration.ofMillis(100);

    /** The timers' poll (§6.4). */
    private static final Duration TIMER_INTERVAL = Duration.ofMillis(100);

    /** Every client's (specs/00-engineering-baseline.md §8). */
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(200);

    @Bean
    RunnerSettings runnerSettings(RunnerProperties runner) {
        return new RunnerSettings(runner.lease(), runner.maxBackoff(), runner.maxConcurrency(), runner.safetyMargin(),
                runner.stuckAfter());
    }

    @Bean
    RiskAssessments riskAssessments(RestClient.Builder builder, RiskProperties risk) {
        return new HttpRiskAssessments(builder.clone().baseUrl(risk.baseUrl().toString()).build(), CONNECT_TIMEOUT);
    }

    /** {@code ASSESS_RISK} of either direction (§6.2). */
    @Bean
    SagaAction assessRiskAction(RiskAssessments risk, RunnerSettings settings, RiskProperties properties,
            Clock clock) {
        return new ByDirection(new AssessRiskAction(risk, settings, properties.timeout().outgoing(), clock),
                new AssessIncomingRiskAction(risk, settings, properties.timeout().incoming(), clock));
    }

    @Bean
    CreatePendingCreditAction createPendingCreditAction(Ledger ledger, RunnerSettings settings,
            LedgerProperties properties, IncomingProperties incoming, Clock clock) {
        return new CreatePendingCreditAction(ledger, settings, properties.timeout(), incoming.verdictGiveUpMargin(),
                clock);
    }

    @Bean
    CancelCreditAction cancelCreditAction(Ledger ledger, RunnerSettings settings, LedgerProperties properties,
            Clock clock) {
        return new CancelCreditAction(ledger, settings, properties.timeout(), clock);
    }

    @Bean
    Ledger ledger(RestClient.Builder builder, LedgerProperties ledger) {
        return new HttpLedger(builder.clone().baseUrl(ledger.baseUrl().toString()).build(), CONNECT_TIMEOUT);
    }

    @Bean
    CreateHoldAction createHoldAction(Ledger ledger, RunnerSettings settings, LedgerProperties properties,
            OutgoingTimings timings, Clock clock) {
        return new CreateHoldAction(ledger, settings, properties.timeout(), timings.holdGiveUpMargin(), clock);
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
    SendAction sendAction(Clock clock) {
        return new SendAction(clock);
    }

    @Bean
    SettleHoldAction settleHoldAction(Ledger ledger, RunnerSettings settings, LedgerProperties properties,
            Clock clock) {
        return new SettleHoldAction(ledger, settings, properties.timeout(), clock);
    }

    @Bean
    ReleaseHoldAction releaseHoldAction(Ledger ledger, RunnerSettings settings, LedgerProperties properties,
            Clock clock) {
        return new ReleaseHoldAction(ledger, settings, properties.timeout(), clock);
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

    @Bean
    IncomingCsmEvents incomingCsmEvents(Payments payments, SagaRunner runner, SagaTelemetry telemetry,
            UuidV7Generator paymentIds, ObjectProvider<Spans> spans) {
        return new IncomingCsmEvents(payments, runner, telemetry, paymentIds, spans.getIfAvailable(Spans::noop));
    }

    @Bean
    CsmEventHandling csmEventHandling(Payments payments, InboxGuard inbox, FinalEvents finalEvents,
            IncomingCsmEvents incoming, SagaRunner runner, SagaTelemetry telemetry, Clock clock) {
        return new CsmEventHandling(payments, inbox, finalEvents, incoming, runner, telemetry, clock);
    }

    /** The application does not start with an empty or non-positive back-off or budget. */
    @Bean
    InvestigationPolicy investigationPolicy(InvestigationProperties investigation) {
        return new InvestigationPolicy(investigation.backoff(), investigation.budget());
    }

    @Bean
    InvestigationTimer investigationTimer(Payments payments, OutboxWriter outbox, InvestigationPolicy policy,
            SagaTelemetry telemetry, Clock clock) {
        return new InvestigationTimer(payments, outbox, policy, telemetry, clock);
    }

    @Bean
    CaseResolutions caseResolutions(ExceptionCases cases, Payments payments, InvestigationTimer investigation,
            CsmEventHandling csmEvents, SagaRunner runner, Clock clock) {
        return new CaseResolutions(cases, payments, investigation, csmEvents, runner, clock);
    }

    @Bean
    OutgoingDeadlineTimer outgoingDeadlineTimer(Payments payments, FinalEvents finalEvents, SagaRunner runner,
            InvestigationTimer investigation, SagaTelemetry telemetry, Clock clock) {
        return new OutgoingDeadlineTimer(payments, finalEvents, runner, investigation, telemetry, clock);
    }

    /**
     * Fires the due timers the service handles (§6.4), each in the poll's transaction; {@code FOR UPDATE SKIP LOCKED}
     * spreads them over the instances. {@code SETTLEMENT_OVERDUE} waits until the incoming flow handles it (M4).
     */
    @Bean
    PollingWorker<DueTimer> timerWorker(OutgoingDeadlineTimer deadline, InvestigationTimer investigation,
            JdbcTemplate jdbc, PlatformTransactionManager transactions, Clock clock, MeterRegistry registry,
            ObjectProvider<Spans> spans) {
        // enum names, safe to inline
        String due = "status = 'SCHEDULED' AND type IN ('" + TimerType.OUTGOING_DEADLINE.name() + "', '"
                + TimerType.INVESTIGATION_RETRY.name() + "') AND due_at <= ?";
        return new PollingWorker<>(new PollingWorker.Settings("payment-timers", 100, TIMER_INTERVAL, 1), jdbc,
                transactions,
                "SELECT timer_id, payment_id, type, due_at, attempt FROM payment_timers WHERE " + due
                        + " ORDER BY due_at LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, n) -> new DueTimer(rs.getLong(1), new PaymentId(rs.getObject(2, UUID.class)),
                        TimerType.valueOf(rs.getString(3)), rs.getTimestamp(4).toInstant(), rs.getInt(5)),
                timer -> {
                    switch (timer.type()) {
                        case OUTGOING_DEADLINE -> deadline.fire(timer);
                        case INVESTIGATION_RETRY -> investigation.fire(timer);
                        case SETTLEMENT_OVERDUE -> throw new IllegalStateException("not picked: " + timer);
                    }
                },
                "SELECT min(due_at) FROM payment_timers WHERE " + due, clock, registry,
                spans.getIfAvailable(Spans::noop));
    }

    /** {@code tpe.runner.*} (§7). */
    @ConfigurationProperties("tpe.runner")
    record RunnerProperties(Duration lease, Duration maxBackoff, int maxConcurrency, Duration safetyMargin,
            Duration stuckAfter) {}

    /** {@code tpe.clients.risk.*} (§7). */
    @ConfigurationProperties("tpe.clients.risk")
    record RiskProperties(URI baseUrl, Timeout timeout) {

        /**
         * @param outgoing {@code ASSESS_RISK} of an outgoing payment and {@code ASSESS_PAYEE_RISK} of an on-us one,
         *     500 ms (UC-04 2c)
         * @param incoming {@code ASSESS_RISK} of an incoming payment, 300 ms (UC-06 3)
         */
        record Timeout(Duration outgoing, Duration incoming) {}
    }

    /** {@code tpe.investigation.*} (§7): the delays between status requests, the last repeating, and the budget. */
    @ConfigurationProperties("tpe.investigation")
    record InvestigationProperties(List<Duration> backoff, Duration budget) {}

    /**
     * {@code tpe.incoming.*} (§7).
     *
     * @param verdictGiveUpMargin before the answer deadline, {@code CREATE_PENDING_CREDIT} gives up: verdict
     *     {@code REJECT MS03}
     * @param settlementOverdue after {@code AnswerSent(ACCP)}, a payment with no settlement goes into investigation
     *     (read by the settlement-overdue timer, which follows)
     */
    @ConfigurationProperties("tpe.incoming")
    record IncomingProperties(Duration verdictGiveUpMargin, Duration settlementOverdue) {}

    /** {@code tpe.clients.ledger.*} (§7). */
    @ConfigurationProperties("tpe.clients.ledger")
    record LedgerProperties(URI baseUrl, Duration timeout) {}
}
