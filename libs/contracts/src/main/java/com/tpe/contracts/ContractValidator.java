package com.tpe.contracts;

import com.tpe.contracts.ContractViolationException.Violation;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.ReasonCode;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Checks a message before it is handled or published (specs/01-shared-libraries.md §4): every component not marked
 * {@link OptionalField} is present, in nested contract records too, and the invariants of 02 hold. Value objects are
 * valid by construction; {@link ContractJson#read} reports invalid ones on the wire.
 */
public final class ContractValidator {

    /** The field code of ledger-service for a malformed customer number (specs/12-ledger-service.md §3.2). */
    static final String INVALID_CUSTOMER_ID = "INVALID_CUSTOMER_ID";

    private static final Pattern CUSTOMER_ID = Pattern.compile("[A-Za-z0-9_-]{1,50}");

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
            case CreditTransferReceived received -> {
                rejectOtherDirection(received.direction(), Direction.INCOMING, violations);
                if (isOutOfOrder(received.receivedAt(), received.answerDeadline())) {
                    violations.add(new Violation("answerDeadline", ContractViolationException.OUT_OF_ORDER));
                }
            }
            case AnswerSent answer -> {
                rejectOtherDirection(answer.direction(), Direction.INCOMING, violations);
                // 02 §4: reasonCode on RJCT only
                if (answer.status() == AnswerStatus.RJCT && answer.reasonCode() == null) {
                    violations.add(new Violation("reasonCode", ContractViolationException.REQUIRED));
                } else if (answer.status() == AnswerStatus.ACCP && answer.reasonCode() != null) {
                    violations.add(new Violation("reasonCode", ContractViolationException.NOT_ALLOWED));
                }
            }
            case CreditTransferSettled settled ->
                    checkPaymentIdMatchesDirection(settled.direction(), settled.paymentId(), violations);
            case CreditTransferRejected rejected ->
                    checkPaymentIdMatchesDirection(rejected.direction(), rejected.paymentId(), violations);
            case CreditTransferNotSent notSent -> {
                rejectOtherDirection(notSent.direction(), Direction.OUTGOING, violations);
                if (notSent.reasonCode() != null && notSent.reasonCode() != ReasonCode.AB03) {
                    violations.add(new Violation("reasonCode", ContractViolationException.NOT_ALLOWED));
                }
            }
            case AccountEvent event -> checkAccount(event, violations);
            case PaymentSettled settled -> {
                // 02 §6.1: the creditor is our customer on incoming and on-us payments
                boolean toOurCustomer =
                        settled.direction() == Direction.INCOMING || Boolean.TRUE.equals(settled.onUs());
                if (toOurCustomer && settled.creditor() != null && settled.creditor().accountId() == null) {
                    violations.add(new Violation("creditor.accountId", ContractViolationException.REQUIRED));
                }
            }
            // an incoming payment may be rejected because the creditor account does not exist (AC01)
            case PaymentRejected rejected -> {}
        }
        if (!violations.isEmpty()) {
            throw new ContractViolationException(Message.contractOf(message.getClass()), violations, null);
        }
    }

    /**
     * Paths of every {@code UNKNOWN} enum constant in {@code message}, in nested records and lists too. Readers map
     * constants they do not know to {@code UNKNOWN} (01 §4); a producer writing one is a bug.
     */
    static List<String> unknownConstants(Message message) {
        List<String> paths = new ArrayList<>();
        collectUnknownConstants((Record) message, "", paths);
        return paths;
    }

    /** {@code earlier} is not strictly before {@code later}; a missing instant is reported as required instead. */
    private static boolean isOutOfOrder(Instant earlier, Instant later) {
        return earlier != null && later != null && !earlier.isBefore(later);
    }

    /** A one-way event has the other known direction; {@code UNKNOWN} is left to the consumer. */
    private static void rejectOtherDirection(Direction actual, Direction expected, List<Violation> violations) {
        if (actual != null && actual != Direction.UNKNOWN && actual != expected) {
            violations.add(new Violation("direction", ContractViolationException.NOT_ALLOWED));
        }
    }

    /** 02 §4: the gateway knows the {@code paymentId} of outgoing transfers only. */
    private static void checkPaymentIdMatchesDirection(Direction direction, PaymentId paymentId,
            List<Violation> violations) {
        if (direction == Direction.OUTGOING && paymentId == null) {
            violations.add(new Violation("paymentId", ContractViolationException.REQUIRED));
        } else if (direction == Direction.INCOMING && paymentId != null) {
            violations.add(new Violation("paymentId", ContractViolationException.NOT_ALLOWED));
        }
    }

    /** 02 §5: revision from 1, no technical accounts; 12 §3.2: an organisation has an id and no date of birth. */
    private static void checkAccount(AccountEvent event, List<Violation> violations) {
        if (event.revision() != null && event.revision() < 1) {
            violations.add(new Violation("revision", ContractViolationException.OUT_OF_RANGE));
        }
        AccountSnapshot account = event.account();
        if (account == null) {
            return;
        }
        // 12 §3.2: the bank's customer number, 1–50 chars [A-Za-z0-9_-]; blank is missing
        if (account.customerId() != null && account.customerId().isBlank()) {
            violations.add(new Violation("account.customerId", ContractViolationException.REQUIRED));
        } else if (account.customerId() != null && !CUSTOMER_ID.matcher(account.customerId()).matches()) {
            violations.add(new Violation("account.customerId", INVALID_CUSTOMER_ID));
        }
        if (account.kind() == AccountKind.TECHNICAL) {
            violations.add(new Violation("account.kind", ContractViolationException.NOT_ALLOWED));
        }
        Holder holder = account.holder();
        if (holder != null && holder.type() == HolderType.ORGANISATION) {
            if (holder.organisationId() == null) {
                violations.add(new Violation("account.holder.organisationId", ContractViolationException.REQUIRED));
            }
            if (holder.dateOfBirth() != null) {
                violations.add(new Violation("account.holder.dateOfBirth", ContractViolationException.NOT_ALLOWED));
            }
        }
    }

    private static void collectUnknownConstants(Record record, String prefix, List<String> paths) {
        for (RecordComponent component : record.getClass().getRecordComponents()) {
            String path = prefix + component.getName();
            Object value = valueOf(record, component);
            if (isUnknownConstant(value)
                    || value instanceof List<?> list && list.stream().anyMatch(ContractValidator::isUnknownConstant)) {
                paths.add(path);
            } else if (value instanceof Record nested && isContractRecord(nested)) {
                collectUnknownConstants(nested, path + ".", paths);
            }
        }
    }

    private static boolean isUnknownConstant(Object value) {
        return value instanceof Enum<?> constant && constant.name().equals(Enums.UNKNOWN);
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
