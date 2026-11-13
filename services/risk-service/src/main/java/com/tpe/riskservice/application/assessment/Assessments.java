package com.tpe.riskservice.application.assessment;

import java.util.Optional;

/** {@code assessments} (specs/14-risk-service.md §5). */
public interface Assessments {

    Optional<StoredAssessment> find(String assessmentId);

    /** Stores {@code assessment} unless its id is stored already; {@code true} if it was stored now. */
    boolean insertIfAbsent(StoredAssessment assessment);
}
