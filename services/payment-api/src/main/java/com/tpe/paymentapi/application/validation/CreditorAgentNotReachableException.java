package com.tpe.paymentapi.application.validation;

/**
 * The creditor's bank is not in the directory or not reachable for SCT Inst: {@code 422 CREDITOR_AGENT_NOT_REACHABLE}
 * (specs/10-payment-api.md §5.2).
 */
public final class CreditorAgentNotReachableException extends RuntimeException {

    public CreditorAgentNotReachableException(String message) {
        super(message);
    }
}
