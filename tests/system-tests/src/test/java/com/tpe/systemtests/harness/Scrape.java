package com.tpe.systemtests.harness;

import com.tpe.observability.test.PrometheusScrape;
import java.net.URI;
import java.util.Map;

/**
 * A service's {@code /actuator/prometheus}, read for one series: what an E2E test asserts on a metric tag
 * (specs/20-testing-strategy.md §2, the {@code cause} of {@code tpe.payment.rejected} and {@code tpe.vop.requests}).
 * The services are shared, so a test compares the value before and after its own payment, never a total (20 §2).
 */
public final class Scrape {

    private Scrape() {}

    /** The sum of {@code series} over every label set that has {@code labels} (others may be there too). */
    public static double value(URI service, String series, Map<String, String> labels) {
        String text = new JsonHttp(service).getText(null, "/actuator/prometheus", "text/plain").body();
        return PrometheusScrape.parse(text).series().stream()
                .filter(found -> found.name().equals(series))
                .filter(found -> found.labels().entrySet().containsAll(labels.entrySet()))
                .mapToDouble(PrometheusScrape.Series::value)
                .sum();
    }
}
