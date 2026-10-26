package com.tpe.systemtests.localstack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** Reads the tables of the specs that the local compose stack must match. */
public final class SpecTables {

    /** A Kafka topic with its local settings; retention -1 is unlimited. */
    public record Topic(String name, int partitions, long retentionMs, String cleanupPolicy) {}

    /** A topic with the modules that may write and read it, which become its ACLs (ADR-015). */
    public record TopicDeclaration(Topic topic, Set<String> producers, Set<String> consumers) {}

    /** A module of the specs/00-engineering-baseline.md §13 table, with its database if it has one. */
    public record LocalService(String module, int port, Optional<String> database) {

        /** The JDBC URL of its database on the local compose stack. */
        public String jdbcUrl() {
            return "jdbc:postgresql://localhost:5432/" + database.orElseThrow();
        }

        /** The password of its local database role, which is named like the database (deploy/postgres/init.sql). */
        public String password() {
            return database.orElseThrow() + "-local";
        }
    }

    private static final String TOPIC = "Topic";
    private static final String PARTITIONS = "Partitions (prod / local)";
    private static final String RETENTION = "Retention";
    private static final String CLEANUP = "Cleanup";
    private static final String PRODUCER_CONSUMERS = "Producer → consumers";
    private static final String DLT_ROW = "<topic>.dlt";
    // the ADR-015 principal that reads every dead-letter topic ("operations tooling" in 02 §2)
    private static final String DLT_READER = "ops-dlt";
    private static final Pattern MODULE = Pattern.compile("[a-z]+(-[a-z0-9]+)+");
    private static final Pattern DAYS = Pattern.compile("(\\d+) d");

    private SpecTables() {}

    /** The topics of specs/02-messaging-contracts.md §2, local partition counts, plus the `.dlt` of each. */
    public static List<Topic> topics(String messagingContracts) {
        return topicDeclarations(messagingContracts).stream().map(TopicDeclaration::topic).toList();
    }

    /**
     * The topics of {@link #topics} with their producers and consumers; a `.dlt` is written by the consumers of its
     * source topic (the messaging DLT handler) and read by {@code ops-dlt}.
     */
    public static List<TopicDeclaration> topicDeclarations(String messagingContracts) {
        var declarations = new ArrayList<TopicDeclaration>();
        Topic dlt = null;
        for (var row : table(messagingContracts, TOPIC)) {
            var topic = new Topic(unquote(row.get(TOPIC)), localPartitions(row.get(PARTITIONS)),
                    retentionMs(row.get(RETENTION)), row.get(CLEANUP).replace("*", ""));
            if (topic.name().equals(DLT_ROW)) {
                dlt = topic;
                continue;
            }
            var sides = row.get(PRODUCER_CONSUMERS).split("→");
            declarations.add(new TopicDeclaration(topic, modules(sides[0]), modules(sides[1])));
        }
        if (dlt == null) {
            throw new IllegalArgumentException("topic table has no " + DLT_ROW + " row");
        }
        for (var source : List.copyOf(declarations)) {
            var topic = new Topic(source.topic().name() + ".dlt", dlt.partitions(), dlt.retentionMs(), dlt.cleanupPolicy());
            declarations.add(new TopicDeclaration(topic, source.consumers(), Set.of(DLT_READER)));
        }
        return declarations;
    }

    /** The modules named in the producer → consumers column of specs/02-messaging-contracts.md §2. */
    public static Set<String> kafkaModules(String messagingContracts) {
        var modules = new TreeSet<String>();
        for (var row : table(messagingContracts, TOPIC)) {
            modules.addAll(modules(row.get(PRODUCER_CONSUMERS).replace("→", ",")));
        }
        return modules;
    }

    /** The services and the simulator of specs/00-engineering-baseline.md §13; infrastructure rows are skipped. */
    public static List<LocalService> localServices(String engineeringBaseline) {
        return table(engineeringBaseline, "Service").stream()
                .filter(row -> MODULE.matcher(row.get("Service")).matches())
                .map(row -> new LocalService(row.get("Service"), Integer.parseInt(row.get("Port")),
                        row.get("Database").startsWith("`") ? Optional.of(unquote(row.get("Database"))) : Optional.empty()))
                .toList();
    }

    /** Module names in a comma-separated cell; anything else (the messaging lib, operations tooling) is skipped. */
    private static Set<String> modules(String cell) {
        var modules = new LinkedHashSet<String>();
        for (var name : cell.split(",")) {
            if (MODULE.matcher(name.strip()).matches()) {
                modules.add(name.strip());
            }
        }
        return modules;
    }

    /** The body rows of the first Markdown table whose header starts with {@code firstHeader}, by column header. */
    private static List<Map<String, String>> table(String markdown, String firstHeader) {
        var rows = new ArrayList<Map<String, String>>();
        List<String> headers = null;
        for (var line : markdown.lines().map(String::strip).toList()) {
            if (!line.startsWith("|")) {
                if (headers != null) {
                    break;
                }
                continue;
            }
            var cells = Arrays.stream(line.substring(1, line.lastIndexOf('|')).split("\\|", -1))
                    .map(String::strip)
                    .toList();
            if (headers == null) {
                headers = cells.getFirst().equals(firstHeader) ? cells : null;
            } else if (!cells.getFirst().startsWith("---")) {
                var row = new LinkedHashMap<String, String>();
                for (int i = 0; i < headers.size(); i++) {
                    row.put(headers.get(i), cells.get(i));
                }
                rows.add(row);
            }
        }
        if (headers == null) {
            throw new IllegalArgumentException("no table with first column " + firstHeader);
        }
        return rows;
    }

    private static int localPartitions(String cell) {
        return Integer.parseInt(cell.substring(cell.indexOf('/') + 1).strip());
    }

    private static long retentionMs(String cell) {
        if (cell.equals("∞")) {
            return -1;
        }
        var days = DAYS.matcher(cell);
        if (!days.matches()) {
            throw new IllegalArgumentException("unexpected retention: " + cell);
        }
        return Duration.ofDays(Long.parseLong(days.group(1))).toMillis();
    }

    private static String unquote(String cell) {
        return cell.substring(cell.indexOf('`') + 1, cell.lastIndexOf('`'));
    }
}
