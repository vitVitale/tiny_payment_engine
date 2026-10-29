package com.tpe.contracts;

import java.util.Arrays;

/**
 * The {@code UNKNOWN} fallback of contract enums (specs/01-shared-libraries.md §4 evolution rules): a constant added
 * by a newer producer is read as {@code UNKNOWN}, and the consumer decides whether it can proceed. Producers never
 * write {@code UNKNOWN}; {@link ContractJson#write} rejects it.
 */
final class Enums {

    static final String UNKNOWN = "UNKNOWN";

    private Enums() {}

    /** The constant of {@code type} named {@code name}, else its {@code UNKNOWN} constant. */
    static <E extends Enum<E>> E orUnknown(Class<E> type, String name) {
        E unknown = Enum.valueOf(type, UNKNOWN);
        return Arrays.stream(type.getEnumConstants())
                .filter(constant -> constant != unknown && constant.name().equals(name))
                .findFirst()
                .orElse(unknown);
    }
}
