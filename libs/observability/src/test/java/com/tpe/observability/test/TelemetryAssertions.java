package com.tpe.observability.test;

/** Entry point of the telemetry contract assertions (specs/20-testing-strategy.md §1, Telemetry contract row). */
public final class TelemetryAssertions {

    private TelemetryAssertions() {}

    public static ScrapeAssert assertThat(PrometheusScrape scrape) {
        return new ScrapeAssert(scrape);
    }
}
