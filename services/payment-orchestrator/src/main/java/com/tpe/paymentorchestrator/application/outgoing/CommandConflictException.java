package com.tpe.paymentorchestrator.application.outgoing;

import com.tpe.paymentdomain.CommandId;

/**
 * A {@code commandId} that already has a payment, submitted with a different request: {@code 409 COMMAND_CONFLICT}.
 * payment-api's idempotency guard should make this impossible, so it is a bug there (specs/11-payment-orchestrator.md
 * §3.1).
 */
public final class CommandConflictException extends RuntimeException {

    public CommandConflictException(CommandId commandId) {
        super("command " + commandId.value() + " already has a payment for a different request");
    }
}
