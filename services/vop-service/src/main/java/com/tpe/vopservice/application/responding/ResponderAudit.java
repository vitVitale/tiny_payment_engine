package com.tpe.vopservice.application.responding;

/** {@code responder_audit} (specs/13-vop-service.md §5, §6.2 step 4): IBAN and name stored as peppered hashes only. */
public interface ResponderAudit {

    void record(AuditedAnswer answer);
}
