package com.tpe.contracts;

import com.tpe.contracts.ContractViolationException.Violation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Checks a message before it is handled or published (specs/01-shared-libraries.md §4): every component not marked
 * {@link OptionalField} is present, in nested contract records too, and the invariants of 02 hold. Value objects are
 * valid by construction; {@link ContractJson#read} reports invalid ones on the wire.
 */
public final class ContractValidator {

    private ContractValidator() {}

    /** Throws {@link ContractViolationException} listing every violation of {@code message}. */
    public static void validate(Message message) {
        List<Violation> violations = new ArrayList<>();
        requireFields((Record) message, "", violations); // every permitted Message is a record
        switch (message) {
            case SendCreditTransfer send -> {
                // 02 §3.1: acceptedAt < sendBy < deadlineAt
                if (isOutOfOrder(send.acceptedAt(), send.sendBy())) {
                    violations.add(new Violation("sendBy", ContractViolationException.OUT_OF_ORDER));
                } else if (isOutOfOrder(send.sendBy(), send.deadlineAt())) {
                    violations.add(new Violation("deadlineAt", ContractViolationException.OUT_OF_ORDER));
                }
            }
            case RequestPaymentStatus request -> {
                // 02 §3: attempt starts at 1
                if (request.attempt() != null && request.attempt() < 1) {
                    violations.add(new Violation("attempt", ContractViolationException.OUT_OF_RANGE));
                }
            }
            case AcceptCreditTransfer accept -> {}
            case RejectCreditTransfer reject -> {}
        }
        if (!violations.isEmpty()) {
            throw new ContractViolationException(Message.contractOf(message.getClass()), violations, null);
        }
    }

    /** {@code earlier} is not strictly before {@code later}; a missing instant is reported as required instead. */
    private static boolean isOutOfOrder(Instant earlier, Instant later) {
        return earlier != null && later != null && !earlier.isBefore(later);
    }

    private static void requireFields(Record record, String prefix, List<Violation> violations) {
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            String path = prefix + component.getName();
            Object value = valueOf(record, component);
            if (value == null) {
                if (!component.isAnnotationPresent(OptionalField.class)) {
                    violations.add(new Violation(path, ContractViolationException.REQUIRED));
                }
            } else if (value instanceof Record nested && isContractRecord(nested)) {
                requireFields(nested, path + ".", violations);
            }
        }
    }

    /** A record of this module, such as {@link Party}; value objects of payment-domain validate themselves. */
    private static boolean isContractRecord(Record record) {
        return record.getClass().getPackage() == ContractValidator.class.getPackage();
    }

    private static Object valueOf(Record record, RecordComponent component) {
        try {
            return component.getAccessor().invoke(record);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("cannot read " + component, e);
        }
    }
}
