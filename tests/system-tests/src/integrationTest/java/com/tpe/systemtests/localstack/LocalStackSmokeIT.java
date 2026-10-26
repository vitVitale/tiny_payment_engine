package com.tpe.systemtests.localstack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.systemtests.localstack.SpecTables.LocalService;
import com.tpe.systemtests.localstack.SpecTables.Topic;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.json.JsonMapper;

/**
 * Smoke check of the local infrastructure (specs/00-engineering-baseline.md §13) against a running
 * {@code docker compose -f deploy/docker-compose.yml up -d --wait postgres kafka oauth2}.
 */
@EnabledIfSystemProperty(named = "tpe.local-stack", matches = "true", disabledReason = "needs the compose stack; run with -Dtpe.local-stack=true")
class LocalStackSmokeIT {

    private static final String INSUFFICIENT_PRIVILEGE = "42501";

    @Test
    void eachServiceRoleLogsInToItsOwnDatabaseOnly() throws Exception {
        var services = localServices().stream().filter(s -> s.database().isPresent()).toList();
        // one database per service (00 §13): guards against a parser that stops early
        assertThat(services).hasSize(7);

        for (var owner : services) {
            var role = owner.database().orElseThrow();
            for (var target : services) {
                var database = target.database().orElseThrow();
                var url = target.jdbcUrl();
                if (owner == target) {
                    try (var connection = DriverManager.getConnection(url, role, owner.password());
                            var rs = connection.createStatement().executeQuery(
                                    "SELECT rolsuper OR rolcreatedb OR rolcreaterole FROM pg_roles WHERE rolname = current_user")) {
                        rs.next();
                        assertThat(rs.getBoolean(1)).as("%s has no admin attributes", role).isFalse();
                    }
                } else {
                    assertThatThrownBy(() -> DriverManager.getConnection(url, role, owner.password()).close())
                            .as("%s must not connect to %s", role, database)
                            .isInstanceOfSatisfying(SQLException.class,
                                    e -> assertThat(e.getSQLState()).isEqualTo(INSUFFICIENT_PRIVILEGE));
                }
            }
        }
    }

    @Test
    void topicsMatchTheTopicTableAndAutoCreationIsOff() throws Exception {
        var expected = SpecTables.topics(Repository.read("specs/02-messaging-contracts.md"));

        try (var admin = Admin.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092",
                AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 10_000,
                AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 15_000))) {
            var names = admin.listTopics().names().get();
            var descriptions = admin.describeTopics(names).allTopicNames().get();
            var resources = names.stream().map(n -> new ConfigResource(ConfigResource.Type.TOPIC, n)).toList();
            var configs = admin.describeConfigs(resources).all().get();
            var actual = new ArrayList<Topic>();
            for (var name : names) {
                var config = configs.get(new ConfigResource(ConfigResource.Type.TOPIC, name));
                actual.add(new Topic(name, descriptions.get(name).partitions().size(),
                        Long.parseLong(config.get("retention.ms").value()), config.get("cleanup.policy").value()));
            }
            assertThat(actual).containsExactlyInAnyOrderElementsOf(expected);

            var broker = new ConfigResource(ConfigResource.Type.BROKER, "1");
            var brokerConfig = admin.describeConfigs(List.of(broker)).all().get().get(broker);
            assertThat(brokerConfig.get("auto.create.topics.enable").value()).isEqualTo("false");
        }
    }

    @Test
    void mockIssuerMintsACustomerTokenWithTheRequiredClaims() throws Exception {
        // the command documented in the README
        var form = Map.of(
                "grant_type", "client_credentials",
                "client_id", "local-channel",
                "client_secret", "local",
                "scope", "payments:write payments:read",
                "sub", "customer-1",
                "accounts", "DE89370400440532013000");
        var body = form.entrySet().stream()
                .map(e -> e.getKey() + "=" + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        var request = HttpRequest.newBuilder(URI.create("http://localhost:8099/default/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        try (var http = HttpClient.newHttpClient()) {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);

            var json = JsonMapper.builder().build();
            var token = json.readTree(response.body()).get("access_token").asString();
            var claims = json.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[1]));
            assertThat(claims.get("iss").asString()).isEqualTo("http://localhost:8099/default");
            assertThat(claims.get("sub").asString()).isEqualTo("customer-1");
            assertThat(claims.get("client_id").asString()).isEqualTo("local-channel");
            assertThat(claims.get("scope").asString()).isEqualTo("payments:write payments:read");
            assertThat(claims.get("accounts").isArray()).isTrue();
            assertThat(claims.get("accounts").values()).extracting(n -> n.asString())
                    .containsExactly("DE89370400440532013000");
        }
    }

    private static List<LocalService> localServices() throws Exception {
        return SpecTables.localServices(Repository.read("specs/00-engineering-baseline.md"));
    }
}
