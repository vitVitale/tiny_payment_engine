package com.tpe.riskservice.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Rule parameters as read from the {@code rules.parameters} JSON (specs/14-risk-service.md §5). */
class RuleParametersTest {

    @Test
    void readsWholeNumbersOfAnyJsonIntegerType() {
        var parameters = new RuleParameters(Map.of("small", 5, "large", 5_000_000_000L));

        assertThat(parameters.longValue("small")).isEqualTo(5L);
        assertThat(parameters.longValue("large")).isEqualTo(5_000_000_000L);
    }

    @Test
    void aMissingOrNonIntegerParameterIsAConfigurationError() {
        var parameters = new RuleParameters(Map.of("text", "abc", "fraction", 1.5));

        assertThatIllegalStateException().isThrownBy(() -> parameters.longValue("missing"))
                .withMessageContaining("missing");
        assertThatIllegalStateException().isThrownBy(() -> parameters.longValue("text"));
        assertThatIllegalStateException().isThrownBy(() -> parameters.longValue("fraction"));
    }

    @Test
    void readsDurationsInIso8601() {
        var parameters = new RuleParameters(Map.of("window", "PT1H", "day", "PT24H"));

        assertThat(parameters.duration("window")).isEqualTo(Duration.ofHours(1));
        assertThat(parameters.duration("day")).isEqualTo(Duration.ofDays(1));
    }

    @Test
    void aMissingOrMalformedDurationIsAConfigurationError() {
        var parameters = new RuleParameters(Map.of("text", "an hour", "number", 3600, "negative", "-PT1H"));

        assertThatIllegalStateException().isThrownBy(() -> parameters.duration("missing"))
                .withMessageContaining("missing");
        assertThatIllegalStateException().isThrownBy(() -> parameters.duration("text"));
        assertThatIllegalStateException().isThrownBy(() -> parameters.duration("number"));
        assertThatIllegalStateException().isThrownBy(() -> parameters.duration("negative"));
    }
}
