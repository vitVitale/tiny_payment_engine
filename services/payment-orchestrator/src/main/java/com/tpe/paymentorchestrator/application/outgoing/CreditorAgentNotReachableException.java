package com.tpe.paymentorchestrator.application.outgoing;

/**
 * The creditor's bank is not in the bank directory or not reachable for SCT Inst: {@code 422
 * CREDITOR_AGENT_NOT_REACHABLE}. payment-api checks this first; the orchestrator checks again
 * (specs/11-payment-orchestrator.md §3.1).
 */
public final class CreditorAgentNotReachableException extends RuntimeException {

    public CreditorAgentNotReachableException() {
        super("the creditor's bank is not reachable for SCT Inst");
    }
}
