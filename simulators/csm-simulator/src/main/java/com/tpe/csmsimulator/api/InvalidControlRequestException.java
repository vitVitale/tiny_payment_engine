package com.tpe.csmsimulator.api;

/** A control request field the simulator cannot act on; answered {@code 400 VALIDATION_FAILED}. */
public final class InvalidControlRequestException extends RuntimeException {

    private final String field;

    public InvalidControlRequestException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
