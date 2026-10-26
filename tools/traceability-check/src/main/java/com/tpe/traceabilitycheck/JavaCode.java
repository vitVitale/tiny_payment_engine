package com.tpe.traceabilitycheck;

import java.util.ArrayList;
import java.util.List;

/**
 * Java source with comments dropped and every string, text-block and char literal replaced by {@code "#n"}, where n
 * indexes its content, so declarations can be read without tripping over ids in comments or strings.
 */
final class JavaCode {

    private final String text;
    private final List<String> literals;

    private JavaCode(String text, List<String> literals) {
        this.text = text;
        this.literals = literals;
    }

    static JavaCode of(String source) {
        var text = new StringBuilder(source.length());
        var literals = new ArrayList<String>();
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (source.startsWith("//", i)) {
                i = indexOrEnd(source, "\n", i);
            } else if (source.startsWith("/*", i)) {
                i = Math.min(indexOrEnd(source, "*/", i + 2) + 2, n);
                text.append(' ');
            } else if (source.startsWith("\"\"\"", i)) {
                int end = indexOrEnd(source, "\"\"\"", i + 3);
                i = Math.min(appendLiteral(text, literals, source.substring(i + 3, end), end + 3), n);
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c && source.charAt(j) != '\n') {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                j = Math.min(j, n);
                i = Math.min(appendLiteral(text, literals, source.substring(i + 1, j), j + 1), n);
            } else {
                text.append(c);
                i++;
            }
        }
        return new JavaCode(text.toString(), List.copyOf(literals));
    }

    String text() {
        return text;
    }

    String literal(int index) {
        return literals.get(index);
    }

    private static int appendLiteral(StringBuilder text, List<String> literals, String content, int next) {
        text.append("\"#").append(literals.size()).append('"');
        literals.add(content);
        return next;
    }

    private static int indexOrEnd(String source, String token, int from) {
        int at = source.indexOf(token, from);
        return at < 0 ? source.length() : at;
    }
}
