package com.tpe.vopservice.application.responding;

/** The requesting PSP is not in the bank directory: {@code 401 UNKNOWN_PARTICIPANT} (specs/13-vop-service.md §3.2). */
public final class UnknownParticipantException extends RuntimeException {

    public UnknownParticipantException() {
        super("the requesting PSP is not a participant of the bank directory");
    }
}
