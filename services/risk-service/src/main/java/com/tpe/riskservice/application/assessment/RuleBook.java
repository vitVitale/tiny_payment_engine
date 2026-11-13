package com.tpe.riskservice.application.assessment;

import com.tpe.riskservice.domain.rules.RuleSet;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The rule parameters in memory (specs/14-risk-service.md §6.1, §7): the assessment path reads {@link #current()}
 * without touching the database; {@link #refreshIfDue()}, run in the background, reloads the {@code rules} table once
 * {@code tpe.risk.rules.refresh-interval} has passed on the service's clock since the last load. A failed reload keeps
 * the rules in memory, and their {@link #age()} (SLO-RSK-03) shows it. Before the first load succeeds there are no
 * rules to decide with: {@link #current()} fails, and the payment is rejected (fail closed, §8). A bean of
 * RiskConfiguration.
 */
public class RuleBook {

    private static final Logger log = LoggerFactory.getLogger(RuleBook.class);

    private final RuleConfigs configs;
    private final Clock clock;
    private final Duration refreshInterval;

    private volatile Loaded loaded;

    private record Loaded(RuleSet rules, Instant at) {}

    public RuleBook(RuleConfigs configs, Clock clock, Duration refreshInterval) {
        this.configs = configs;
        this.clock = clock;
        this.refreshInterval = refreshInterval;
    }

    /**
     * The rules last loaded.
     *
     * @throws RulesNotLoadedException if no load has succeeded yet
     */
    public RuleSet current() {
        Loaded current = loaded;
        if (current == null) {
            throw new RulesNotLoadedException();
        }
        return current.rules();
    }

    /** Reloads if never loaded or {@code refresh-interval} has passed since the last load. */
    public void refreshIfDue() {
        Loaded current = loaded;
        if (current == null || !clock.instant().isBefore(current.at().plus(refreshInterval))) {
            try {
                load();
            } catch (RuntimeException e) {
                log.warn("Rules could not be reloaded; the rules loaded at {} stay in use",
                        current == null ? null : current.at(), e);
            }
        }
    }

    /** Time since the rules in memory were loaded; {@code null} before the first load. */
    public Duration age() {
        Loaded current = loaded;
        return current == null ? null : Duration.between(current.at(), clock.instant());
    }

    private synchronized void load() {
        Instant at = clock.instant();
        loaded = new Loaded(configs.loadAll(), at);
    }
}
