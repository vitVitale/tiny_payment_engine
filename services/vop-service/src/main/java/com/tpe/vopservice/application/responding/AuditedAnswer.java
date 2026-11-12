package com.tpe.vopservice.application.responding;

import com.tpe.vopservice.domain.matching.VerificationResult;
import java.time.Instant;
import java.util.Objects;

/**
 * What {@link ResponderAudit} records of one answer: the request in clear, which the repository stores with IBAN and
 * name hashed only (specs/13-vop-service.md §5).
 */
public record AuditedAnswer(SchemeVerification request, VerificationResult result, Instant at) {

    public AuditedAnswer {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(result, "result");
        Objects.requireNonNull(at, "at");
    }
}
