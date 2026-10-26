package com.tpe.traceabilitycheck;

import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The ids the specs require tests for. */
final class SpecIds {

    private static final String MENTION_REGEX = "(?<![A-Za-z0-9-])(?:" + IdKind.ACCEPTANCE_CRITERION.idRegex() + "|"
            + IdKind.END_TO_END.idRegex() + "|" + IdKind.CHAOS.idRegex() + ")(?![A-Za-z0-9])";
    private static final Pattern MENTION = Pattern.compile(MENTION_REGEX);
    // a list continuing a full id: "ISO-AC-01, -02", "E2E-O-06, -I-11", "E2E-O-12 and -O-21", "ORC-AC-21 … -26",
    // "CH-01 … CH-17"; group 1 separator, 2 E2E letter, 3 number, 4 full id
    private static final Pattern CONTINUATION = Pattern.compile("(\\s*,\\s*|\\s+and\\s+|\\s*(?:…|\\.\\.\\.)\\s*)"
            + "(?:-([A-Z]-)?(\\d{2})(?![A-Za-z0-9])|(" + MENTION_REGEX + "))");
    private static final Pattern RANGE = Pattern.compile("\\s*(?:…|\\.\\.\\.)\\s*");
    private static final Pattern INVENTORY_HEADING = Pattern.compile("(?m)^#+\\s*10\\.1\\s.*$");
    private static final Pattern NEXT_HEADING = Pattern.compile("(?m)^#");
    private static final Pattern INVENTORY_ROW =
            Pattern.compile("(?m)^\\|\\s*(" + IdKind.INTEGRATION.idRegex() + ")\\s*\\|");
    private static final String TESTING_STRATEGY = "20-testing-strategy.md";

    private SpecIds() {}

    /**
     * Every acceptance criterion, E2E and chaos id mentioned in any spec, short forms and ranges included, and every
     * integration row of the §10.1 inventory in specs/20-testing-strategy.md.
     */
    static Map<String, IdKind> required(Map<String, String> specs) {
        var ids = new TreeMap<String, IdKind>();
        specs.forEach((path, text) -> {
            var mention = MENTION.matcher(text);
            int from = 0;
            while (mention.find(from)) {
                from = addWithContinuation(text, mention, ids);
            }
            if (path.endsWith(TESTING_STRATEGY)) {
                INVENTORY_ROW.matcher(inventorySection(text)).results()
                        .forEach(m -> ids.put(m.group(1), IdKind.INTEGRATION));
            }
        });
        return ids;
    }

    /** Adds the id {@code mention} found and the list continuing it; returns where the search goes on. */
    private static int addWithContinuation(String text, Matcher mention, Map<String, IdKind> ids) {
        var id = mention.group();
        put(id, ids);
        var next = CONTINUATION.matcher(text).useTransparentBounds(true);
        int at = mention.end();
        while (next.region(at, text.length()).lookingAt()) {
            var prefix = id.substring(0, id.lastIndexOf('-') + 1);
            var following = next.group(4) != null
                    ? next.group(4)
                    : (next.group(2) == null ? prefix : "E2E-" + next.group(2)) + next.group(3);
            if (RANGE.matcher(next.group(1)).matches() && sameSeries(id, following)) {
                for (int n = number(id) + 1; n < number(following); n++) {
                    put(prefix + "%02d".formatted(n), ids);
                }
            }
            put(following, ids);
            id = following;
            at = next.end();
        }
        return at;
    }

    private static boolean sameSeries(String from, String to) {
        return from.substring(0, from.lastIndexOf('-')).equals(to.substring(0, to.lastIndexOf('-')));
    }

    private static int number(String id) {
        return Integer.parseInt(id.substring(id.lastIndexOf('-') + 1));
    }

    private static void put(String id, Map<String, IdKind> ids) {
        IdKind.of(id).ifPresent(kind -> ids.put(id, kind));
    }

    private static String inventorySection(String text) {
        var heading = INVENTORY_HEADING.matcher(text);
        if (!heading.find()) {
            return "";
        }
        var end = NEXT_HEADING.matcher(text);
        return text.substring(heading.end(), end.find(heading.end()) ? end.start() : text.length());
    }
}
