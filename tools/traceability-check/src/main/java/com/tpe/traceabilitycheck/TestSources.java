package com.tpe.traceabilitycheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Reads which ids the test sources carry. A test carries an id in the name or a {@code @Tag} of a test method
 * (annotated {@code @Test}, {@code @ParameterizedTest}, … or jqwik {@code @Property}) or of a class declared in a file
 * that has test methods. Ids in comments, strings, helper methods and fields do not count.
 */
final class TestSources {

    /** Ids carried by any test, and the subsets carried in consumer pact test and provider verification files. */
    record Carried(Set<String> any, Set<String> consumer, Set<String> provider) {

        /** What {@code id} still lacks: nothing when it is covered. */
        List<Violation.Reason> missing(String id, IdKind kind) {
            if (kind != IdKind.INTEGRATION) {
                return any.contains(id) ? List.of() : List.of(Violation.Reason.NO_TEST);
            }
            var missing = new ArrayList<Violation.Reason>();
            if (!consumer.contains(id)) {
                missing.add(Violation.Reason.NO_CONSUMER_PACT_TEST);
            }
            if (!provider.contains(id)) {
                missing.add(Violation.Reason.NO_PROVIDER_VERIFICATION);
            }
            return missing;
        }
    }

    private static final Set<String> TEST_ANNOTATIONS = Set.of(
            "Test", "ParameterizedTest", "RepeatedTest", "TestFactory", "TestTemplate", "Property", "Example");
    private static final Set<String> TYPE_KEYWORDS = Set.of("class", "interface", "record", "enum");
    // words that may stand before "name(" without it being a method declaration
    private static final Set<String> NOT_A_RETURN_TYPE = Set.of(
            "new", "return", "throw", "else", "case", "assert", "yield", "if", "while", "for", "switch", "catch",
            "synchronized", "this", "super", "do", "try");
    private static final Pattern TAG_VALUE = Pattern.compile("@Tag\\s*\\(\\s*(?:value\\s*=\\s*)?\"#(\\d+)\"\\s*\\)");

    private TestSources() {}

    static Carried scan(Map<String, String> tests) {
        var carried = new Carried(new TreeSet<>(), new TreeSet<>(), new TreeSet<>());
        for (var source : tests.values()) {
            var file = Declarations.of(JavaCode.of(source));
            if (file.testMethods().isEmpty()) {
                continue;
            }
            var ids = new TreeSet<String>();
            for (var declaration : file.carriers()) {
                for (var kind : IdKind.values()) {
                    kind.idsInName(declaration.name()).forEach(ids::add);
                }
                ids.addAll(declaration.tags());
            }
            carried.any().addAll(ids);
            if (file.annotations().contains("PactTestFor")) {
                carried.consumer().addAll(ids);
            }
            if (file.annotations().contains("Provider")) {
                carried.provider().addAll(ids);
            }
        }
        return carried;
    }

    private record Declaration(String name, List<String> tags) {}

    /** The declarations of one file that may carry an id, and the simple names of every annotation in it. */
    private record Declarations(List<Declaration> classes, List<Declaration> testMethods, Set<String> annotations) {

        List<Declaration> carriers() {
            var all = new ArrayList<>(classes);
            all.addAll(testMethods);
            return all;
        }

        static Declarations of(JavaCode code) {
            var text = code.text();
            var result = new Declarations(new ArrayList<>(), new ArrayList<>(), new TreeSet<>());
            var pendingNames = new ArrayList<String>();
            var pendingTags = new ArrayList<String>();
            String previous = "";
            int i = 0;
            int n = text.length();
            while (i < n) {
                char c = text.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                } else if (c == '@') {
                    int start = i;
                    i = skipQualifiedName(text, i + 1);
                    var qualified = text.substring(start + 1, i).replaceAll("\\s", "");
                    var name = qualified.substring(qualified.lastIndexOf('.') + 1);
                    int args = skipWhitespace(text, i);
                    if (args < n && text.charAt(args) == '(') {
                        i = closingParen(text, args) + 1;
                    }
                    if (name.equals("interface")) {
                        previous = "interface";
                        continue;
                    }
                    result.annotations().add(name);
                    pendingNames.add(name);
                    // @Tag("x") alone or inside @Tags({...})
                    TAG_VALUE.matcher(text.substring(start, i)).results()
                            .forEach(m -> pendingTags.add(code.literal(Integer.parseInt(m.group(1))).strip()));
                } else if (Character.isJavaIdentifierStart(c)) {
                    int end = i + 1;
                    while (end < n && Character.isJavaIdentifierPart(text.charAt(end))) {
                        end++;
                    }
                    var word = text.substring(i, end);
                    i = end;
                    if (TYPE_KEYWORDS.contains(previous)) {
                        result.classes().add(new Declaration(word, List.copyOf(pendingTags)));
                        pendingNames.clear();
                        pendingTags.clear();
                    } else {
                        int after = skipWhitespace(text, i);
                        boolean declaresMethod = after < n && text.charAt(after) == '('
                                && isReturnType(previous) && !NOT_A_RETURN_TYPE.contains(word);
                        if (declaresMethod && pendingNames.stream().anyMatch(TEST_ANNOTATIONS::contains)) {
                            result.testMethods().add(new Declaration(word, List.copyOf(pendingTags)));
                        }
                        if (declaresMethod) {
                            pendingNames.clear();
                            pendingTags.clear();
                        }
                    }
                    previous = word;
                } else {
                    if (c == ';' || c == '{' || c == '}') {
                        pendingNames.clear();
                        pendingTags.clear();
                    }
                    previous = String.valueOf(c);
                    i++;
                }
            }
            return result;
        }

        private static boolean isReturnType(String previous) {
            return previous.equals(">") || previous.equals("]")
                    || (!previous.isEmpty() && Character.isJavaIdentifierStart(previous.charAt(0))
                            && !NOT_A_RETURN_TYPE.contains(previous));
        }

        private static int skipQualifiedName(String text, int i) {
            while (i < text.length() && (Character.isJavaIdentifierPart(text.charAt(i)) || text.charAt(i) == '.')) {
                i++;
            }
            return i;
        }

        private static int skipWhitespace(String text, int i) {
            while (i < text.length() && Character.isWhitespace(text.charAt(i))) {
                i++;
            }
            return i;
        }

        private static int closingParen(String text, int open) {
            int depth = 0;
            for (int i = open; i < text.length(); i++) {
                if (text.charAt(i) == '(') {
                    depth++;
                } else if (text.charAt(i) == ')' && --depth == 0) {
                    return i;
                }
            }
            return text.length() - 1;
        }
    }
}
