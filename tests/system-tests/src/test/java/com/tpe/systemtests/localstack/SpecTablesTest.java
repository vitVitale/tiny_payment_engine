package com.tpe.systemtests.localstack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.tpe.systemtests.localstack.SpecTables.LocalService;
import com.tpe.systemtests.localstack.SpecTables.Topic;
import com.tpe.systemtests.localstack.SpecTables.TopicDeclaration;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SpecTablesTest {

    private static final String TOPICS = """
            ## 2. Topics

            | Topic | Key | Partitions (prod / local) | Retention | Cleanup | Producer → consumers |
            |---|---|---|---|---|---|
            | `csm.outbound.commands` | `txId` | 12 / 3 | 7 d | delete | payment-orchestrator → csm-gateway |
            | `accounts.events` | `accountId` | 6 / 3 | ∞ | **compact** | ledger-service → vop-service, risk-service |
            | `<topic>.dlt` | as source | 1 | 30 d | delete | `messaging` lib → operations tooling |

            Replication factor 3 and `min.insync.replicas=2` in production, 1 locally.

            ## 3. `csm.outbound.commands` (orchestrator → gateway)

            | Field | Type |
            |---|---|
            | `txId` | `String` |
            """;

    private static final String LOCAL_DEVELOPMENT = """
            ## 13. Local development

            | Service | Port | Database |
            |---|---|---|
            | payment-api | 8080 | `api` |
            | ledger-service | 8082 | `ledger` |
            | csm-simulator | 8090 | — (in memory) |
            | PostgreSQL | 5432 | |
            | mock OAuth2 issuer | 8099 | |

            Profiles: `local`.

            ## 14. Continuous integration
            """;

    @Test
    void topicsAreTheRowsOfTheTopicTableWithTheirLocalSettingsAndOneDltEach() {
        assertThat(SpecTables.topics(TOPICS)).containsExactly(
                new Topic("csm.outbound.commands", 3, 604_800_000L, "delete"),
                new Topic("accounts.events", 3, -1L, "compact"),
                new Topic("csm.outbound.commands.dlt", 1, 2_592_000_000L, "delete"),
                new Topic("accounts.events.dlt", 1, 2_592_000_000L, "delete"));
    }

    @Test
    void declarationsCarryTheAclsOfAdr015() {
        // producers and consumers of the table; a DLT is written by the source's consumers and read by ops-dlt
        assertThat(SpecTables.topicDeclarations(TOPICS)).extracting(
                        d -> d.topic().name(), TopicDeclaration::producers, TopicDeclaration::consumers)
                .containsExactly(
                        tuple("csm.outbound.commands", Set.of("payment-orchestrator"), Set.of("csm-gateway")),
                        tuple("accounts.events", Set.of("ledger-service"), Set.of("vop-service", "risk-service")),
                        tuple("csm.outbound.commands.dlt", Set.of("csm-gateway"), Set.of("ops-dlt")),
                        tuple("accounts.events.dlt", Set.of("vop-service", "risk-service"), Set.of("ops-dlt")));
    }

    @Test
    void kafkaModulesAreTheProducersAndConsumersOfTheTopicTable() {
        assertThat(SpecTables.kafkaModules(TOPICS))
                .containsExactlyInAnyOrder("payment-orchestrator", "csm-gateway", "ledger-service", "vop-service", "risk-service");
    }

    @Test
    void localServicesAreTheModulesOfTheLocalDevelopmentTable() {
        assertThat(SpecTables.localServices(LOCAL_DEVELOPMENT)).containsExactly(
                new LocalService("payment-api", 8080, Optional.of("api")),
                new LocalService("ledger-service", 8082, Optional.of("ledger")),
                new LocalService("csm-simulator", 8090, Optional.empty()));
    }

    @Test
    void aLocalDatabaseRoleHasTheDatabaseNameAndTheLocalPassword() {
        var ledger = new LocalService("ledger-service", 8082, Optional.of("ledger"));

        assertThat(ledger.jdbcUrl()).isEqualTo("jdbc:postgresql://localhost:5432/ledger");
        assertThat(ledger.password()).isEqualTo("ledger-local");
    }
}
