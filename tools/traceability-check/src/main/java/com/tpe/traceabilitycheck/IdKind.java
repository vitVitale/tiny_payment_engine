package com.tpe.traceabilitycheck;

import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The kinds of traceable id (specs/20-testing-strategy.md §1, Traceability row): how each is written in the specs and
 * in test names.
 */
enum IdKind {
    ACCEPTANCE_CRITERION("[A-Z]+-AC-\\d{2}", "[A-Z]+_AC_\\d{2}", name -> name.replace('_', '-')),
    END_TO_END("E2E-[A-Z]-\\d{2}", "E2E_[A-Z]_\\d{2}", name -> name.replace('_', '-')),
    CHAOS("CH-\\d{2}", "CH_\\d{2}", name -> name.replace('_', '-')),
    /** Integrations of the §10.1 inventory; they need a consumer pact test and a provider verification. */
    INTEGRATION("[PM]-\\d{2}", "PACT_[PM]\\d{2}", name -> name.charAt(5) + "-" + name.substring(6));

    private final Pattern id;
    private final Pattern inName;
    private final UnaryOperator<String> nameToId;

    IdKind(String id, String name, UnaryOperator<String> nameToId) {
        this.id = Pattern.compile(id);
        // a name may hold the id after an underscore (CH_05_KafkaPartitionTest, fooBar_MSG_AC_03) but not inside a word
        this.inName = Pattern.compile("(?<![A-Za-z0-9])" + name + "(?!\\d)");
        this.nameToId = nameToId;
    }

    /** Matches the id itself as written in the specs, without boundaries. */
    String idRegex() {
        return id.pattern();
    }

    static Optional<IdKind> of(String id) {
        for (var kind : values()) {
            if (kind.id.matcher(id).matches()) {
                return Optional.of(kind);
            }
        }
        return Optional.empty();
    }

    /** The ids of this kind carried by a class or method name. */
    java.util.stream.Stream<String> idsInName(String name) {
        return inName.matcher(name).results().map(m -> nameToId.apply(m.group()));
    }
}
