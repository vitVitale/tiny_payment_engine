package com.tpe.observability.test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A parsed {@code /actuator/prometheus} response in the Prometheus text or OpenMetrics format: one {@link Series} per
 * sample line. Comments, {@code # EOF}, timestamps and exemplars are skipped.
 */
public final class PrometheusScrape {

    /** One sample: {@code name{labels} value}. */
    public record Series(String name, Map<String, String> labels, double value) {}

    private final List<Series> series;

    private PrometheusScrape(List<Series> series) {
        this.series = List.copyOf(series);
    }

    public static PrometheusScrape parse(String text) {
        List<Series> parsed = new ArrayList<>();
        for (String line : text.split("\n")) {
            String sample = line.strip();
            if (!sample.isEmpty() && !sample.startsWith("#")) {
                parsed.add(parseLine(sample));
            }
        }
        return new PrometheusScrape(parsed);
    }

    public List<Series> series() {
        return series;
    }

    private static Series parseLine(String line) {
        int nameEnd = 0;
        while (nameEnd < line.length() && line.charAt(nameEnd) != '{' && line.charAt(nameEnd) != ' ') {
            nameEnd++;
        }
        String name = line.substring(0, nameEnd);
        Map<String, String> labels = new LinkedHashMap<>();
        int position = nameEnd;
        if (position < line.length() && line.charAt(position) == '{') {
            position = parseLabels(line, position + 1, labels);
        }
        String rest = line.substring(position).strip();
        int valueEnd = rest.indexOf(' ');
        String value = valueEnd < 0 ? rest : rest.substring(0, valueEnd);
        return new Series(name, Collections.unmodifiableMap(labels), Double.parseDouble(value));
    }

    /** Reads {@code k="v",…} up to the closing brace; returns the index after it. */
    private static int parseLabels(String line, int start, Map<String, String> labels) {
        int position = start;
        while (line.charAt(position) != '}') {
            int equals = line.indexOf('=', position);
            String key = line.substring(position, equals).strip();
            StringBuilder value = new StringBuilder();
            position = equals + 2; // skip ="
            while (line.charAt(position) != '"') {
                char c = line.charAt(position);
                if (c == '\\') {
                    char escaped = line.charAt(++position);
                    value.append(escaped == 'n' ? '\n' : escaped);
                } else {
                    value.append(c);
                }
                position++;
            }
            labels.put(key, value.toString());
            position++; // closing quote
            if (line.charAt(position) == ',') {
                position++;
            }
        }
        return position + 1;
    }
}
