package com.tpe.csmsimulator.application;

/** An incoming payment the CSM cannot send: the field the test has to give or change. */
public class InvalidIncomingPaymentException extends RuntimeException {

    private final String field;

    public InvalidIncomingPaymentException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
