package com.tpe.messaging.housekeeping;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 01 §3.1: the inbox outlives every consumed {@code delete} topic, so a message replayed from Kafka is still
 * deduplicated. Read from {@code deploy/kafka/topics.yml}, the source of the topics; the DLTs are read by operations
 * tooling, not by an inbox, and the compacted {@code accounts.events} is exempt (its consumers deduplicate by
 * revision).
 */
class MessagingRetentionDefaultsTest {

    private static final Path TOPICS = Path.of("../../deploy/kafka/topics.yml");

    @Test
    void theDefaultInboxRetentionIsLongerThanTheRetentionOfEveryConsumedDeleteTopic() throws IOException {
        Map<String, Duration> consumedDeleteTopics = consumedDeleteTopics();

        assertThat(consumedDeleteTopics).containsKeys("csm.outbound.commands", "csm.inbound.events", "payments.events")
                .doesNotContainKey("accounts.events");
        assertThat(consumedDeleteTopics).allSatisfy((topic, retention) ->
                assertThat(MessagingRetention.Settings.defaults().inbox()).as(topic).isGreaterThan(retention));
    }

    @Test
    void theDefaultsAreThoseOfTheSpec() {
        assertThat(MessagingRetention.Settings.defaults().outbox()).isEqualTo(Duration.ofDays(7));
        assertThat(MessagingRetention.Settings.defaults().inbox()).isEqualTo(Duration.ofDays(8));
    }

    /** Topic → retention of the {@code delete} topics that a service consumes (not only {@code ops-dlt}). */
    private static Map<String, Duration> consumedDeleteTopics() throws IOException {
        Map<String, Duration> topics = new LinkedHashMap<>();
        for (Map<String, String> topic : topics()) {
            boolean delete = "delete".equals(topic.get("cleanup-policy"));
            boolean consumedByAService = !topic.getOrDefault("consumers", "[]").matches("\\[\\s*(ops-dlt)?\\s*]");
            if (delete && consumedByAService) {
                topics.put(topic.get("name"), Duration.ofMillis(Long.parseLong(topic.get("retention-ms"))));
            }
        }
        return topics;
    }

    /** The "key: value" entries of each topic; the file keeps one per line (see its header). */
    private static List<Map<String, String>> topics() throws IOException {
        List<Map<String, String>> topics = new ArrayList<>();
        for (String line : Files.readAllLines(TOPICS)) {
            String entry = line.replaceFirst("#.*", "").strip();
            if (entry.startsWith("- name:")) {
                topics.add(new LinkedHashMap<>());
                entry = entry.substring(2);
            }
            int colon = entry.indexOf(':');
            if (!topics.isEmpty() && colon > 0) {
                topics.getLast().put(entry.substring(0, colon).strip(), entry.substring(colon + 1).strip());
            }
        }
        return topics;
    }
}
