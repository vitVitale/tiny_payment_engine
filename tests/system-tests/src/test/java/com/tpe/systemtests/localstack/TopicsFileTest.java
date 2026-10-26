package com.tpe.systemtests.localstack;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.systemtests.localstack.SpecTables.Topic;
import com.tpe.systemtests.localstack.SpecTables.TopicDeclaration;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Configuration-consistency test of ADR-015: deploy/kafka/topics.yml declares exactly the topics and ACLs of 02 §2. */
class TopicsFileTest {

    @Test
    void topicsFileMatchesTheTopicTable() throws IOException {
        var expected = SpecTables.topicDeclarations(Repository.read("specs/02-messaging-contracts.md"));

        assertThat(topicsFile()).containsExactlyInAnyOrderElementsOf(expected);
    }

    @SuppressWarnings("unchecked")
    private static List<TopicDeclaration> topicsFile() throws IOException {
        Map<String, List<Map<String, Object>>> file = new Yaml().load(Repository.read("deploy/kafka/topics.yml"));
        return file.get("topics").stream()
                .map(t -> new TopicDeclaration(
                        new Topic((String) t.get("name"), ((Number) t.get("partitions")).intValue(),
                                ((Number) t.get("retention-ms")).longValue(), (String) t.get("cleanup-policy")),
                        new LinkedHashSet<>((List<String>) t.get("producers")),
                        new LinkedHashSet<>((List<String>) t.get("consumers"))))
                .toList();
    }
}
