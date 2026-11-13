package com.tpe.riskservice.application.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.paymentdomain.MutableClock;
import com.tpe.riskservice.domain.rules.RuleAction;
import com.tpe.riskservice.domain.rules.RuleConfig;
import com.tpe.riskservice.domain.rules.RuleDirection;
import com.tpe.riskservice.domain.rules.RuleParameters;
import com.tpe.riskservice.domain.rules.RuleSet;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The rules in memory (specs/14-risk-service.md §6.1, §7, §8): reloaded once {@code refresh-interval} has passed on
 * the clock, kept when a reload fails, and no decision at all before the first load (fail closed).
 */
class RuleBookTest {

    private static final Duration INTERVAL = Duration.ofSeconds(30);

    private final MutableClock clock = new MutableClock(Instant.parse("2026-11-12T08:00:00Z"));
    private final FakeConfigs configs = new FakeConfigs();
    private final RuleBook book = new RuleBook(configs, clock, INTERVAL);

    @Test
    void beforeTheFirstLoadThereAreNoRulesToDecideWith() {
        assertThatThrownBy(book::current).isInstanceOf(RulesNotLoadedException.class);
        assertThat(book.age()).isNull();
        assertThat(configs.loads).isZero();
    }

    @Test
    void reloadsOnlyOnceTheIntervalHasPassedOnTheClock() {
        configs.limit = 1;
        book.refreshIfDue();
        configs.limit = 2;

        clock.advance(INTERVAL.minusMillis(1));
        book.refreshIfDue();
        assertThat(limit()).isEqualTo(1);
        assertThat(book.age()).isEqualTo(INTERVAL.minusMillis(1));

        clock.advance(Duration.ofMillis(1));
        book.refreshIfDue();
        assertThat(limit()).isEqualTo(2);
        assertThat(book.age()).isZero();
        assertThat(configs.loads).isEqualTo(2);
    }

    @Test
    void aFailedReloadKeepsTheRulesAndTheirAge() {
        configs.limit = 1;
        book.refreshIfDue();
        configs.failing = true;

        clock.advance(INTERVAL);
        book.refreshIfDue();

        assertThat(limit()).isEqualTo(1);
        assertThat(book.age()).isEqualTo(INTERVAL);
    }

    @Test
    void aFailedFirstLoadLeavesNoRules() {
        configs.failing = true;

        book.refreshIfDue();

        assertThatThrownBy(book::current).isInstanceOf(RulesNotLoadedException.class);
    }

    private long limit() {
        return book.current().get("AMOUNT_LIMIT").orElseThrow().parameters().longValue("maxAmountMinor");
    }

    private static final class FakeConfigs implements RuleConfigs {

        long limit;
        boolean failing;
        int loads;

        @Override
        public RuleSet loadAll() {
            if (failing) {
                throw new IllegalStateException("database down");
            }
            loads++;
            return RuleSet.of(List.of(new RuleConfig("AMOUNT_LIMIT", true, RuleDirection.BOTH,
                    new RuleParameters(Map.of("maxAmountMinor", limit)), RuleAction.DENY)));
        }
    }
}
