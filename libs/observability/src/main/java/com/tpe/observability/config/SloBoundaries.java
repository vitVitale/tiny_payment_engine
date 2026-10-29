package com.tpe.observability.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Histogram boundaries of every latency timer with an SLO, so each threshold of specs/25-service-levels.md §5 is an
 * exact bucket (§11). Applied as defaults of {@code management.metrics.distribution.slo.<meter>}; a meter name also
 * covers meters whose names start with it.
 */
public final class SloBoundaries {

    /** Meter name → boundaries, ascending. The Ids name the SLO each threshold comes from. */
    public static final Map<String, List<Duration>> DEFAULTS = defaults();

    private SloBoundaries() {}

    private static Map<String, List<Duration>> defaults() {
        Map<String, List<Duration>> slos = new LinkedHashMap<>();
        // 25 §11
        slos.put("http.server.requests", ms(50, 100, 200, 300, 500, 1000, 2000, 5000, 5500));
        // SLO-ORC-03 … -05
        slos.put("tpe.payment.stage.duration", ms(500, 1000, 1200, 3000, 5000));
        // 25 §11; SLO-ORC-07
        slos.put("tpe.payment.duration", ms(1000, 3000, 10_000, 20_000));
        // SLO-GW-02, -04, -08
        slos.put("tpe.csm.stage.duration", ms(50, 100, 2000));
        // 25 §11; SLO-GW-06. Not 0s: Micrometer needs positive boundaries, and a timer records no negative margin
        slos.put("tpe.csm.answer.margin", ms(1000, 3000));
        // SLO-VOP-02, -04
        slos.put("tpe.vop.requests", ms(200, 1000, 2000, 5200));
        // SLO-RSK-02
        slos.put("tpe.risk.assessment.duration", ms(50, 250));
        // SLO-RSK-05
        slos.put("tpe.sanctions.run.duration", ms(3_600_000));
        // SLO-RSK-06
        slos.put("tpe.sanctions.freeze.latency", ms(60_000, 900_000));
        // SLO-NTF-01, -02
        slos.put("tpe.notification.dispatch.delay", ms(5000, 30_000));
        slos.put("tpe.notification.attempt.lateness", ms(5000));
        // SLO-MSG-01, SLO-LED-04
        slos.put("tpe.outbox.publish.latency", ms(50, 1000));
        return Collections.unmodifiableMap(slos);
    }

    /** {@code boundaries} as a {@code management.metrics.distribution.slo.*} value: {@code 50ms,100ms}. */
    static String asProperty(List<Duration> boundaries) {
        return boundaries.stream().map(boundary -> boundary.toMillis() + "ms").collect(Collectors.joining(","));
    }

    private static List<Duration> ms(long... millis) {
        return Arrays.stream(millis).mapToObj(Duration::ofMillis).toList();
    }
}
