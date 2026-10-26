package com.tpe.systemtests.localstack;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/** Every module of the specs/00-engineering-baseline.md §13 table has a `local` profile pointing at the compose stack. */
class LocalProfilesTest {

    @Test
    void everyModuleHasALocalProfileWithItsPortAndTheComposeHosts() throws IOException {
        var kafkaModules = SpecTables.kafkaModules(Repository.read("specs/02-messaging-contracts.md"));
        var modules = SpecTables.localServices(Repository.read("specs/00-engineering-baseline.md"));
        // the seven services and the simulator: guards against a parser that stops early
        assertThat(modules).hasSize(8);

        for (var module : modules) {
            var dir = module.module().endsWith("-simulator") ? "simulators" : "services";
            var profile = flatten(Repository.path(dir + "/" + module.module() + "/src/main/resources/application-local.yml"));
            var expected = new LinkedHashMap<String, Object>();
            expected.put("server.port", module.port());
            module.database().ifPresent(db -> {
                expected.put("spring.datasource.url", module.jdbcUrl());
                expected.put("spring.datasource.username", db);
                expected.put("spring.datasource.password", module.password());
            });
            if (kafkaModules.contains(module.module())) {
                expected.put("spring.kafka.bootstrap-servers", "localhost:9092");
            }
            if (dir.equals("services")) {
                expected.put("spring.security.oauth2.resourceserver.jwt.issuer-uri", "http://localhost:8099/default");
            }

            assertThat(profile).as(module.module()).containsAllEntriesOf(expected);
            if (!kafkaModules.contains(module.module())) {
                assertThat(profile).as(module.module()).doesNotContainKey("spring.kafka.bootstrap-servers");
            }
        }
    }

    private static Map<String, Object> flatten(Path yaml) throws IOException {
        assertThat(yaml).exists();
        Map<String, Object> tree = new Yaml().load(Files.readString(yaml));
        var flat = new LinkedHashMap<String, Object>();
        flatten("", tree, flat);
        return flat;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(String prefix, Map<String, Object> tree, Map<String, Object> flat) {
        tree.forEach((key, value) -> {
            if (value instanceof Map<?, ?> child) {
                flatten(prefix + key + ".", (Map<String, Object>) child, flat);
            } else {
                flat.put(prefix + key, value);
            }
        });
    }
}
