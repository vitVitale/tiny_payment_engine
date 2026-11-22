package com.tpe.systemtests.harness;

import com.tpe.messaging.housekeeping.MessagingRetention;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.Yaml;

/**
 * The timing rules of the configuration-consistency gate (specs/20-testing-strategy.md §1), as checks returning one
 * line per violation. {@link ConfigurationConsistencyTest} runs them on the services' default configuration and on
 * seeded violations.
 */
final class ConfigurationConsistency {

    private static final Path REPOSITORY = Path.of("../..");
    private static final String INBOX_RETENTION = "tpe.messaging.inbox.retention";
    /** {@code ≤ 50 ms}, {@code ≤ 5.2 s}: an SLO threshold in an SLI cell of 25 §5. */
    private static final Pattern THRESHOLD = Pattern.compile("≤ (\\d+(?:\\.\\d+)?) (ms|s)\\b");

    /** A topic of deploy/kafka/topics.yml; an unlimited retention ({@code -1}) is {@link ChronoUnit#FOREVER}. */
    record TopicRetention(String name, Duration retention, String cleanupPolicy, Set<String> consumers) {}

    /** A caller timeout of 25 §7: the caller's configuration key and the SLO of the callee it guards against. */
    record CallerTimeout(String caller, String key, String calleeSlo) {}

    /** The hops of 25 §7 whose callee this repository owns. */
    static final List<CallerTimeout> CALLER_TIMEOUTS = List.of(
            new CallerTimeout("payment-api", "tpe.clients.orchestrator.timeout", "SLO-ORC-02"),
            new CallerTimeout("payment-api", "tpe.clients.orchestrator.status-timeout", "SLO-ORC-02"),
            new CallerTimeout("payment-api", "tpe.clients.vop.check-timeout", "SLO-VOP-01"),
            new CallerTimeout("payment-api", "tpe.clients.vop.verify-timeout", "SLO-VOP-02"),
            new CallerTimeout("payment-orchestrator", "tpe.clients.risk.timeout.outgoing", "SLO-RSK-02"),
            new CallerTimeout("payment-orchestrator", "tpe.clients.risk.timeout.incoming", "SLO-RSK-02"),
            new CallerTimeout("payment-orchestrator", "tpe.clients.ledger.timeout", "SLO-LED-02"));

    /** Consumers in topics.yml that are not modules of this repository: the DLT readers of ADR-015. */
    static final Set<String> OUTSIDE_REPOSITORY = Set.of("ops-dlt");

    private static final Supplier<String> SERVICE_LEVELS = memoize(() -> read("specs/25-service-levels.md"));

    private ConfigurationConsistency() {}

    private static Supplier<String> memoize(Supplier<String> source) {
        var value = new AtomicReference<String>();
        return () -> value.updateAndGet(v -> v != null ? v : source.get());
    }

    /**
     * Every consumer's inbox retention must lie above the retention of each {@code delete} topic it consumes, so a
     * redelivered record still finds its inbox row (01 §3.1). Only the readers of {@link #OUTSIDE_REPOSITORY} have no
     * configuration here; any other consumer without one is a violation, so a typo in topics.yml cannot pass.
     */
    static List<String> inboxViolations(List<TopicRetention> topics,
            Function<String, Optional<ServiceProfile>> profiles) {
        var violations = new ArrayList<String>();
        for (TopicRetention topic : topics) {
            if (!topic.cleanupPolicy().equals("delete")) {
                continue;
            }
            for (String consumer : topic.consumers()) {
                if (OUTSIDE_REPOSITORY.contains(consumer)) {
                    continue;
                }
                Optional<ServiceProfile> profile = profiles.apply(consumer);
                if (profile.isEmpty()) {
                    violations.add(consumer + ": consumes " + topic.name() + " but has no configuration");
                    continue;
                }
                Duration inbox = profile.get().duration(INBOX_RETENTION)
                        .orElse(MessagingRetention.Settings.defaults().inbox());
                if (inbox.compareTo(topic.retention()) <= 0) {
                    violations.add(consumer + ": " + INBOX_RETENTION + " " + inbox + " is not above the retention "
                            + topic.retention() + " of " + topic.name());
                }
            }
        }
        return violations;
    }

    /**
     * Every caller timeout must lie above the callee's highest-percentile SLO threshold (25 §7). For the ledger the
     * configured value is the cap of {@code min(1 s, remaining − margin)}; the remaining time is not configuration.
     */
    static List<String> timeoutViolations(List<CallerTimeout> timeouts, Function<String, Duration> thresholds,
            Function<String, Optional<ServiceProfile>> profiles) {
        var violations = new ArrayList<String>();
        for (CallerTimeout timeout : timeouts) {
            Optional<ServiceProfile> profile = profiles.apply(timeout.caller());
            if (profile.isEmpty()) {
                violations.add(timeout.caller() + ": has no configuration");
                continue;
            }
            Optional<Duration> configured = profile.get().duration(timeout.key());
            Duration threshold = thresholds.apply(timeout.calleeSlo());
            if (configured.isEmpty()) {
                violations.add(timeout.caller() + ": " + timeout.key() + " is not set");
            } else if (configured.get().compareTo(threshold) <= 0) {
                violations.add(timeout.caller() + ": " + timeout.key() + " " + configured.get()
                        + " is not above " + threshold + " of " + timeout.calleeSlo());
            }
        }
        return violations;
    }

    /**
     * The highest threshold in the SLI cell of {@code slo}'s row of 25 §5, e.g. 250 ms for SLO-RSK-02. Read from the
     * spec until the SLO definition files exist; the SLO-files ticket switches this to {@code deploy/slo}.
     */
    static Duration thresholdOf(String slo) {
        String row = SERVICE_LEVELS.get().lines()
                .filter(line -> line.startsWith("| " + slo + " |"))
                .findFirst().orElseThrow(() -> new IllegalStateException(slo + " is not in 25 §5"));
        String sli = row.split("\\|")[2];
        return THRESHOLD.matcher(sli).results()
                .map(m -> new BigDecimal(m.group(1)).multiply(m.group(2).equals("s") ? BigDecimal.valueOf(1000)
                        : BigDecimal.ONE))
                .map(millis -> Duration.ofMillis(millis.longValueExact()))
                .max(Comparator.naturalOrder())
                .orElseThrow(() -> new IllegalStateException(slo + " has no threshold: " + sli));
    }

    /** The default configuration (no profile) of a module of this repository, empty for anything else. */
    static Optional<ServiceProfile> defaultProfile(String service) {
        Path resources = ServiceJar.inRepository(service).resources();
        return Files.isDirectory(resources) ? Optional.of(ServiceProfile.of(resources)) : Optional.empty();
    }

    /** The topics of deploy/kafka/topics.yml. */
    @SuppressWarnings("unchecked")
    static List<TopicRetention> topicsFile() {
        Map<String, List<Map<String, Object>>> file = new Yaml().load(read("deploy/kafka/topics.yml"));
        return file.get("topics").stream()
                .map(t -> {
                    long ms = ((Number) t.get("retention-ms")).longValue();
                    return new TopicRetention((String) t.get("name"),
                            ms < 0 ? ChronoUnit.FOREVER.getDuration() : Duration.ofMillis(ms),
                            (String) t.get("cleanup-policy"),
                            new LinkedHashSet<>((List<String>) t.get("consumers")));
                })
                .toList();
    }

    private static String read(String relative) {
        try {
            return Files.readString(REPOSITORY.resolve(relative));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
