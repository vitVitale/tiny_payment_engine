package com.tpe.observability.test;

import com.tpe.observability.config.SloBoundaries;
import com.tpe.observability.config.TpeMeters;
import com.tpe.observability.test.PrometheusScrape.Series;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.assertj.core.api.AbstractAssert;

/**
 * Assertions on a {@link PrometheusScrape} for the telemetry contract test (specs/20-testing-strategy.md §1): a meter
 * exists with its tags and an exact bucket at each SLO threshold, every series carries the common tags, and no tag is
 * outside the allow-list of {@link TpeMeters} or high-cardinality (specs/00-engineering-baseline.md §11). Meters are
 * named as in code ({@code tpe.payment.duration}); the Prometheus suffixes ({@code _seconds_bucket}, {@code _total},
 * …) are matched here.
 */
public final class ScrapeAssert extends AbstractAssert<ScrapeAssert, PrometheusScrape> {

    /** Label keys Prometheus adds to histogram and summary series. */
    private static final Set<String> STRUCTURAL_LABELS = Set.of("le", "quantile");

    private static final Set<String> SUFFIXES = Set.of("seconds", "seconds_bucket", "seconds_count", "seconds_sum",
            "seconds_max", "total", "bucket", "count", "sum", "max", "created", "seconds_created");

    /** The Kafka client metrics of Spring Boot. */
    private static final String KAFKA_CLIENT_PREFIX = "kafka_";

    /** 26 §4.1: never tags. Compared lower case without separators, so {@code tx_id} is {@code txId}. */
    private static final Set<String> HIGH_CARDINALITY = Set.of("paymentid", "txid", "uetr", "iban", "debtoriban",
            "creditoriban", "clientid", "endtoendid", "msgid", "commandid", "eventid", "traceid", "spanid", "accountid",
            "customerid", "exceptionmessage");

    ScrapeAssert(PrometheusScrape actual) {
        super(actual, ScrapeAssert.class);
    }

    /** At least one series of {@code meter}. */
    public ScrapeAssert hasMeter(String meter) {
        isNotNull();
        if (seriesOf(meter).findAny().isEmpty()) {
            failWithMessage("Expected a series of %s (%s*) in the scrape, found none", meter, prometheusName(meter));
        }
        return this;
    }

    /** A series of {@code meter} that carries every key of {@code tagKeys}. */
    public ScrapeAssert hasMeterWithTags(String meter, String... tagKeys) {
        hasMeter(meter);
        List<String> keys = Arrays.asList(tagKeys);
        if (seriesOf(meter).noneMatch(series -> series.labels().keySet().containsAll(keys))) {
            failWithMessage("Expected a series of %s with tags %s, found label sets %s", meter, keys,
                    seriesOf(meter).map(series -> series.labels().keySet()).distinct().toList());
        }
        return this;
    }

    /** A {@code _bucket} series of {@code meter} with {@code le} exactly at {@code threshold}. */
    public ScrapeAssert hasBucketAt(String meter, Duration threshold) {
        isNotNull();
        double seconds = threshold.toNanos() / 1e9;
        boolean found = seriesOf(meter)
                .filter(series -> series.name().endsWith("_bucket"))
                .map(series -> series.labels().get("le"))
                .anyMatch(le -> le != null && !le.equals("+Inf") && Double.parseDouble(le) == seconds);
        if (!found) {
            failWithMessage("Expected %s to have a bucket le=%s (%s)", meter, seconds, threshold);
        }
        return this;
    }

    /** Exact buckets at every default SLO boundary of {@code meter} ({@link SloBoundaries#DEFAULTS}). */
    public ScrapeAssert hasSloBuckets(String meter) {
        List<Duration> thresholds = Optional.ofNullable(SloBoundaries.DEFAULTS.get(meter))
                .orElseThrow(() -> new IllegalArgumentException(meter + " has no SLO boundaries"));
        thresholds.forEach(threshold -> hasBucketAt(meter, threshold));
        return this;
    }

    /** Every series carries {@code application} and {@code version} with these values (26 §3). */
    public ScrapeAssert carriesCommonTags(String application, String version) {
        isNotNull();
        Map<String, String> expected = Map.of(TpeMeters.APPLICATION, application, TpeMeters.VERSION, version);
        List<String> missing = actual.series().stream()
                .filter(series -> !series.labels().entrySet().containsAll(expected.entrySet()))
                .map(series -> series.name() + series.labels())
                .distinct()
                .toList();
        if (actual.series().isEmpty() || !missing.isEmpty()) {
            failWithMessage("Expected every series to carry %s, but %d do not: %s", expected, missing.size(),
                    missing.stream().limit(10).toList());
        }
        return this;
    }

    /** Every {@code tpe_*} series belongs to a meter of {@link TpeMeters} and carries only its declared tag keys. */
    public ScrapeAssert usesOnlyAllowedTags() {
        isNotNull();
        List<String> failures = new ArrayList<>();
        actual.series().stream().filter(series -> series.name().startsWith("tpe_")).forEach(series -> {
            Optional<String> meter = declaredMeterOf(series.name());
            if (meter.isEmpty()) {
                failures.add(series.name() + " is not declared in TpeMeters");
                return;
            }
            Set<String> extra = new HashSet<>(series.labels().keySet());
            extra.removeAll(TpeMeters.tagKeys(meter.get()));
            extra.removeAll(TpeMeters.COMMON_TAG_KEYS);
            extra.removeAll(STRUCTURAL_LABELS);
            if (!extra.isEmpty()) {
                failures.add(series.name() + " has tag keys outside the allow-list: " + extra);
            }
        });
        failIfAny("Telemetry contract: label allow-list (specs/26-observability.md §4.1)", failures);
        return this;
    }

    /**
     * No series has a high-cardinality tag key such as {@code paymentId} (00 §11, 26 §4.1). The {@code client_id} of
     * the Kafka client metrics, kept as is (26 §4.1), names a consumer or producer of the service, not an API client.
     */
    public ScrapeAssert hasNoHighCardinalityTags() {
        isNotNull();
        List<String> failures = actual.series().stream()
                .flatMap(series -> series.labels().keySet().stream()
                        .filter(key -> HIGH_CARDINALITY.contains(normalise(key)))
                        .filter(key -> !(series.name().startsWith(KAFKA_CLIENT_PREFIX) && key.equals("client_id")))
                        .map(key -> series.name() + " has high-cardinality tag " + key))
                .distinct()
                .toList();
        failIfAny("Telemetry contract: high-cardinality tags (specs/00-engineering-baseline.md §11)", failures);
        return this;
    }

    /** {@link #usesOnlyAllowedTags()} and {@link #hasNoHighCardinalityTags()}. */
    public ScrapeAssert satisfiesTelemetryContract() {
        return hasNoHighCardinalityTags().usesOnlyAllowedTags();
    }

    private void failIfAny(String rule, List<String> failures) {
        if (!failures.isEmpty()) {
            failWithMessage("%s, %d violation(s):%n  %s", rule, failures.size(), String.join("\n  ", failures));
        }
    }

    private Stream<Series> seriesOf(String meter) {
        String base = prometheusName(meter);
        return actual.series().stream().filter(series -> belongsTo(series.name(), base));
    }

    private static Optional<String> declaredMeterOf(String seriesName) {
        return TpeMeters.all().keySet().stream()
                .filter(meter -> belongsTo(seriesName, prometheusName(meter)))
                .findFirst();
    }

    private static boolean belongsTo(String seriesName, String base) {
        return seriesName.equals(base)
                || seriesName.startsWith(base + "_") && SUFFIXES.contains(seriesName.substring(base.length() + 1));
    }

    private static String prometheusName(String meter) {
        return meter.replace('.', '_');
    }

    private static String normalise(String key) {
        return key.replaceAll("[_.\\-]", "").toLowerCase(Locale.ROOT);
    }
}
