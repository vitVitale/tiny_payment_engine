package com.tpe.contracts;

import java.util.List;
import java.util.stream.Collectors;

/**
 * A message breaks its contract: a required field is missing, a value object is invalid, an invariant does not hold
 * or the JSON cannot be read. Consumers do not retry it; the {@code messaging} error handler sends it to the DLT
 * (specs/01-shared-libraries.md §3.6, §4). The message names the type, field paths and codes, never input values,
 * so it is safe to log (specs/26-observability.md §4.4).
 */
public final class ContractViolationException extends RuntimeException {

    /** A required field is missing. */
    public static final String REQUIRED = "REQUIRED";
    /** Instants that must be strictly ordered are not, e.g. {@code acceptedAt < sendBy < deadlineAt}. */
    public static final String OUT_OF_ORDER = "OUT_OF_ORDER";
    /** A number outside its allowed range, e.g. a status request {@code attempt} below 1. */
    public static final String OUT_OF_RANGE = "OUT_OF_RANGE";
    /** A producer tried to write the {@code UNKNOWN} fallback constant of an enum. */
    public static final String UNKNOWN_CONSTANT = "UNKNOWN_CONSTANT";
    /** The JSON is not well-formed, or a field has the wrong JSON type or format. */
    public static final String MALFORMED = "MALFORMED";

    /**
     * {@code field} is the dotted path in the record ({@code debtor.iban}), empty for the whole message; {@code code}
     * is one of the constants above or a {@code payment-domain} {@code ValidationCode} such as {@code INVALID_IBAN}.
     */
    public record Violation(String field, String code) {

        @Override
        public String toString() {
            return field.isEmpty() ? code : field + " " + code;
        }
    }

    private final Contract contract;
    private final transient List<Violation> violations;

    ContractViolationException(Contract contract, List<Violation> violations, Throwable cause) {
        super(contract.type() + " v" + contract.version() + " violates its contract: "
                + violations.stream().map(Violation::toString).collect(Collectors.joining(", ", "[", "]")), cause);
        this.contract = contract;
        this.violations = List.copyOf(violations);
    }

    public String messageType() {
        return contract.type();
    }

    public int schemaVersion() {
        return contract.version();
    }

    public List<Violation> violations() {
        return violations;
    }
}
