package com.tpe.csmgateway.infrastructure.timers;

import com.tpe.csmgateway.application.answer.AnswerCoordinator;
import com.tpe.csmgateway.application.answer.AnswerCoordinator.Due;
import com.tpe.messaging.worker.PollingWorker;
import com.tpe.observability.context.Spans;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.TxId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The answer deadline scheduler (specs/15-csm-gateway.md §2, §6.3): a {@link PollingWorker} that hands every incoming
 * transfer with no answer past its {@code answer_deadline} to {@link AnswerCoordinator#deadline}, locked with
 * {@code SKIP LOCKED}, so it runs on every instance and each transfer is answered once.
 */
@Configuration(proxyBeanMethods = false)
class AnswerDeadlineConfiguration {

    /** The answer deadline fires within 100 ms after {@code answer_deadline} p99 (15 §8): half of it at most waiting. */
    static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    private static final String DUE = "answer_status IS NULL AND answer_deadline <= ?";

    @Bean
    PollingWorker<Due> answerDeadlineWorker(AnswerCoordinator coordinator, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, Clock clock, MeterRegistry registry,
            ObjectProvider<Spans> spans) {
        return new PollingWorker<>(new PollingWorker.Settings("answer-deadline", 100, POLL_INTERVAL, 1), jdbc,
                transactions,
                "SELECT debtor_agent_bic, tx_id FROM incoming_transfers WHERE " + DUE
                        + " ORDER BY answer_deadline LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, n) -> new Due(new Bic(rs.getString("debtor_agent_bic")), new TxId(rs.getString("tx_id"))),
                coordinator::deadline,
                "SELECT min(answer_deadline) FROM incoming_transfers WHERE " + DUE, clock, registry,
                spans.getIfAvailable(Spans::noop));
    }
}
