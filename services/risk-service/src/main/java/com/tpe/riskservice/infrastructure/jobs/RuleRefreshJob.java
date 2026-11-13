package com.tpe.riskservice.infrastructure.jobs;

import com.tpe.riskservice.application.assessment.RuleBook;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Keeps the rules in memory fresh (specs/14-risk-service.md §6.1, §7): every second it asks the {@link RuleBook}
 * whether {@code refresh-interval} has passed on the service's clock, so the assessment path never waits for a
 * reload. The first load runs before the application reports ready. Plus the gauge {@code tpe.risk.rules.age} of SLO-RSK-03 (25 §5.5): seconds since the last load.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
class RuleRefreshJob {

    private final RuleBook rules;

    RuleRefreshJob(RuleBook rules) {
        this.rules = rules;
    }

    @Scheduled(initialDelay = 1000, fixedDelay = 1000)
    void refresh() {
        rules.refreshIfDue();
    }

    /** The first load, while the application starts: a failure is logged, and the scheduled refresh retries it. */
    @Bean
    ApplicationRunner initialRuleLoad() {
        return arguments -> rules.refreshIfDue();
    }

    @Bean
    Gauge riskRulesAge(MeterRegistry registry) {
        return Gauge.builder("tpe.risk.rules.age", rules, book -> {
                    Duration age = book.age();
                    return age == null ? Double.NaN : age.toMillis() / 1000.0;
                })
                .description("Seconds since the rule parameters in memory were loaded")
                .baseUnit("seconds")
                .register(registry);
    }
}
