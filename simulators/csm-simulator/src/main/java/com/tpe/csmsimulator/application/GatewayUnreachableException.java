package com.tpe.csmsimulator.application;

/** csm-gateway gave no answer at all: the connection was refused, reset or timed out. */
public class GatewayUnreachableException extends RuntimeException {

    public GatewayUnreachableException(Throwable cause) {
        super("csm-gateway gave no answer: " + cause, cause);
    }
}
