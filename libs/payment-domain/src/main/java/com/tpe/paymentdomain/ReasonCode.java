package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.ValidationCode.INVALID_REASON_CODE;
import static com.tpe.paymentdomain.ValidationCode.REQUIRED;

import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The reason codes of the glossary plus {@code MS03} (Q-01), each with its ISO 20022 definition
 * (specs/01-shared-libraries.md §1.2).
 */
public enum ReasonCode implements StatusReason {
    AB03("Settlement aborted due to timeout."),
    AB05("Transaction stopped due to timeout at the Creditor Agent."),
    AB06("Transaction stopped due to timeout at the Instructed Agent."),
    AC01("Account number is invalid or missing."),
    AC04("Account number specified has been closed on the bank of account's books."),
    AC06("Account specified is blocked, prohibiting posting of transactions against it."),
    AG01("Transaction forbidden on this type of account (formerly NoAgreement)"),
    AM04("Amount of funds available to cover specified message amount is insufficient."),
    FF01("File Format incomplete or invalid"),
    FR01("Returned as a result of fraud."),
    MS03("Reason has not been specified by agent."),
    RR04("Regulatory Reason");

    private static final Map<String, ReasonCode> BY_CODE =
            Arrays.stream(values()).collect(Collectors.toUnmodifiableMap(ReasonCode::code, Function.identity()));

    private final String description;

    ReasonCode(String description) {
        this.description = description;
    }

    /** A reason code from an ISO message: the constant if this design knows it, {@link Other} otherwise. */
    public static StatusReason parse(String code) {
        Objects.requireNonNull(code, "code");
        ReasonCode known = BY_CODE.get(code);
        return known != null ? known : new Other(code);
    }

    @Override
    public String code() {
        return name();
    }

    public String description() {
        return description;
    }

    /**
     * A reason code this design does not use, kept as received so it is never dropped. A known code is always its
     * {@link ReasonCode} constant, never an {@code Other}.
     */
    public record Other(String code) implements StatusReason {

        public Other {
            Objects.requireNonNull(code, "code");
            if (code.isBlank()) {
                throw new DomainValidationException(REQUIRED, "reason code is empty");
            }
            if (BY_CODE.containsKey(code)) {
                throw new DomainValidationException(INVALID_REASON_CODE, "known reason code; use ReasonCode.parse");
            }
        }
    }
}
