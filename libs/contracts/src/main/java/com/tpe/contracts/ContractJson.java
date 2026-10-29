package com.tpe.contracts;

import com.tpe.contracts.ContractViolationException.Violation;
import com.tpe.paymentdomain.DomainValidationException;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads and writes contract messages as JSON with {@link ContractsModule}. {@link #read} returns only messages that
 * pass {@link ContractValidator}; anything else, invalid value objects included, is a
 * {@link ContractViolationException}. {@link #write} validates before writing. Thread-safe.
 */
public final class ContractJson {

    private final JsonMapper mapper = JsonMapper.builder().addModule(new ContractsModule()).build();

    /** The validated message of {@code json}. */
    public <T extends Message> T read(String json, Class<T> type) {
        Objects.requireNonNull(json, "json");
        Contract contract = Message.contractOf(type);
        T message;
        try {
            message = mapper.readValue(json, type);
        } catch (JacksonException e) {
            // the cause may quote the input; it is kept for DEBUG only (specs/26-observability.md §4.4)
            throw new ContractViolationException(contract, List.of(violation(e)), e);
        }
        if (message == null) {
            throw new ContractViolationException(contract,
                    List.of(new Violation("", ContractViolationException.MALFORMED)), null);
        }
        ContractValidator.validate(message);
        return message;
    }

    /** The JSON body of {@code message}; type and version go into headers (02 §1). */
    public String write(Message message) {
        ContractValidator.validate(message);
        // UNKNOWN is the reader's fallback for constants it does not know (01 §4); producers never write it
        List<Violation> unknown = ContractValidator.unknownConstants(message).stream()
                .map(path -> new Violation(path, ContractViolationException.UNKNOWN_CONSTANT))
                .toList();
        if (!unknown.isEmpty()) {
            throw new ContractViolationException(Message.contractOf(message.getClass()), unknown, null);
        }
        return mapper.writeValueAsString(message);
    }

    /** Field and code of a failed read: the value object's code, REQUIRED for a missing part, else MALFORMED. */
    private static Violation violation(JacksonException e) {
        String field = e.getPath().stream()
                .map(JacksonException.Reference::getPropertyName)
                .filter(Objects::nonNull)
                .collect(Collectors.joining("."));
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof DomainValidationException invalid) {
                return new Violation(field, invalid.code().name());
            }
            if (cause instanceof NullPointerException) {
                return new Violation(field, ContractViolationException.REQUIRED);
            }
        }
        return new Violation(field, ContractViolationException.MALFORMED);
    }
}
