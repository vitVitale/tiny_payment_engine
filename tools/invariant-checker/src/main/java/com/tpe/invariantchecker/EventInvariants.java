package com.tpe.invariantchecker;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The invariants of specs/20-testing-strategy.md §3 that look at Kafka records a consumer collected (the system tests'
 * test consumer, the soak's): pure functions of what was read, so each has a plain negative test.
 *
 * <ul>
 *   <li><b>INV-7</b> exactly one {@code PaymentSettled} / {@code PaymentRejected} per final payment, of its outcome, and
 *   none for a payment that is not final.</li>
 *   <li><b>INV-9</b>, its DLT part: no dead-letter record at all (the case part is
 *   {@link PaymentInvariants#noUnexpectedCases}).</li>
 * </ul>
 */
public final class EventInvariants {

    /** A record of {@code payments.events}: its key and its {@code tpe-message-type}. */
    public record FinalEvent(UUID paymentId, String type) {}

    /** A record of a {@code <topic>.dlt} topic, with the reason the DLT publisher gave. */
    public record DeadLetter(String topic, String key, String reason) {}

    private static final Map<String, String> EVENT_OF_STATE = Map.of(
            "SETTLED", "PaymentSettled", "REJECTED", "PaymentRejected");

    private EventInvariants() {}

    /**
     * INV-7 for the payments of {@code states} (id → orchestrator state); events of other payments are ignored, the
     * topic being shared (20 §2).
     */
    public static List<Violation> finalEvents(Map<UUID, String> states, List<FinalEvent> events) {
        var violations = new ArrayList<Violation>();
        states.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(payment -> {
            List<String> types = events.stream().filter(event -> event.paymentId().equals(payment.getKey()))
                    .map(FinalEvent::type).toList();
            String expected = EVENT_OF_STATE.get(payment.getValue());
            List<String> wanted = expected == null ? List.of() : List.of(expected);
            if (!types.equals(wanted)) {
                violations.add(new Violation("INV-7", payment.getKey().toString(),
                        payment.getValue() + ": final events " + types + ", expected " + wanted));
            }
        });
        return violations;
    }

    /** INV-9, the DLT part: one violation per dead letter. */
    public static List<Violation> noDeadLetters(List<DeadLetter> deadLetters) {
        return deadLetters.stream()
                .map(dead -> new Violation("INV-9", dead.topic() + "/" + dead.key(), "dead letter: " + dead.reason()))
                .toList();
    }
}
