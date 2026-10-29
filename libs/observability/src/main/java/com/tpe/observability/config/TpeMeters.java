package com.tpe.observability.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The label allow-list (specs/26-observability.md §4.1): every {@code tpe.*} meter and the only tag keys it may carry,
 * besides the common tags. The telemetry contract test fails on an undeclared meter or tag key. Declared here: the
 * meters of specs/00-engineering-baseline.md §11, specs/25-service-levels.md §11 and the self metrics; a service
 * declares its own meters here when it adds them. Tags are low-cardinality only, never {@code paymentId}.
 */
public final class TpeMeters {

    /** Common tag of every series: the {@code service.name} (26 §3). */
    public static final String APPLICATION = "application";
    /** Common tag of every series: the {@code service.version}, release version + commit SHA (26 §3). */
    public static final String VERSION = "version";
    public static final Set<String> COMMON_TAG_KEYS = Set.of(APPLICATION, VERSION);

    private static final Map<String, Set<String>> TAG_KEYS = declare();

    private TpeMeters() {}

    /** Every declared meter name and its tag keys. */
    public static Map<String, Set<String>> all() {
        return TAG_KEYS;
    }

    /** The tag keys of a declared meter. */
    public static Set<String> tagKeys(String meter) {
        Set<String> keys = TAG_KEYS.get(meter);
        if (keys == null) {
            throw new IllegalArgumentException(meter + " is not declared in TpeMeters");
        }
        return keys;
    }

    private static Map<String, Set<String>> declare() {
        Map<String, Set<String>> meters = new LinkedHashMap<>();
        // 00 §11, provided by messaging
        meters.put("tpe.outbox.pending", Set.of());
        meters.put("tpe.outbox.publish.latency", Set.of());
        meters.put("tpe.inbox.duplicates", Set.of());
        meters.put("tpe.dlt.messages", Set.of("topic"));
        meters.put("tpe.worker.lag", Set.of("worker"));
        meters.put("tpe.relay.active", Set.of());
        // 25 §11 and the service specs that own them
        meters.put("tpe.payment.stage.duration", Set.of("direction", "stage"));
        meters.put("tpe.payment.rejected", Set.of("direction", "reason", "cause"));
        meters.put("tpe.payment.duration", Set.of("direction", "outcome"));
        meters.put("tpe.csm.stage.duration", Set.of("stage"));
        meters.put("tpe.csm.answer.margin", Set.of());
        // 25 §5 SLO-GW-03, -05
        meters.put("tpe.csm.answer", Set.of("status", "decidedBy"));
        meters.put("tpe.csm.notsent", Set.of("reason"));
        meters.put("tpe.vop.requests", Set.of("role", "route", "result", "cause"));
        meters.put("tpe.vop.retention.overdue", Set.of());
        meters.put("tpe.risk.assessment.duration", Set.of("direction", "decision"));
        meters.put("tpe.risk.rules.age", Set.of());
        meters.put("tpe.sanctions.list.age", Set.of());
        meters.put("tpe.sanctions.run.duration", Set.of("type"));
        meters.put("tpe.sanctions.run.last_success", Set.of("type"));
        meters.put("tpe.sanctions.freeze.latency", Set.of());
        meters.put("tpe.notification.dispatch.delay", Set.of("format"));
        meters.put("tpe.notification.attempt.lateness", Set.of("format"));
        meters.put("tpe.notification.deliveries", Set.of("outcome"));
        // 26 §7: tools/consumer-lag-exporter
        meters.put("tpe.consumer.lag.seconds", Set.of("topic", "group", "partition"));
        meters.put("tpe.consumer.lag.records", Set.of("topic", "group", "partition"));
        // 26 §4.1 self metrics
        meters.put("tpe.telemetry.spans.dropped", Set.of());
        return Collections.unmodifiableMap(meters);
    }
}
