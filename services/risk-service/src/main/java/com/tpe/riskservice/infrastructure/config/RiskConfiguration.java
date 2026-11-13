package com.tpe.riskservice.infrastructure.config;

import com.tpe.contracts.ContractsModule;
import com.tpe.riskservice.application.assessment.RuleBook;
import com.tpe.riskservice.application.assessment.RuleConfigs;
import com.tpe.riskservice.domain.rules.AmountLimitRule;
import com.tpe.riskservice.domain.rules.MuleNewAccountRule;
import com.tpe.riskservice.domain.rules.NewPayeeHighAmountRule;
import com.tpe.riskservice.domain.rules.NonEuSanctionsRule;
import com.tpe.riskservice.domain.rules.RuleEngine;
import com.tpe.riskservice.domain.rules.VelocityAmountRule;
import com.tpe.riskservice.domain.rules.VelocityCountRule;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Time, JSON of the internal API, the rule engine with the implemented rules, and the rule book
 * (specs/00-engineering-baseline.md §6, specs/14-risk-service.md §3.1, §6.1, §7).
 */
@Configuration(proxyBeanMethods = false)
class RiskConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** The value objects of the {@code contracts} DTOs as strings (01 §4); Spring Boot registers it with Jackson. */
    @Bean
    ContractsModule contractsModule() {
        return new ContractsModule();
    }

    /** The rules implemented so far; the other seeded configurations wait for theirs (§6.1). */
    @Bean
    RuleEngine ruleEngine() {
        return new RuleEngine(List.of(new AmountLimitRule(), new VelocityCountRule(), new VelocityAmountRule(),
                new NewPayeeHighAmountRule(), new MuleNewAccountRule(), new NonEuSanctionsRule()));
    }

    @Bean
    RuleBook ruleBook(RuleConfigs configs, Clock clock,
            @Value("${tpe.risk.rules.refresh-interval}") Duration refreshInterval) {
        return new RuleBook(configs, clock, refreshInterval);
    }
}
