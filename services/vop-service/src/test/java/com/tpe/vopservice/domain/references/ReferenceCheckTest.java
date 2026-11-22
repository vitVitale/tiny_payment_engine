package com.tpe.vopservice.domain.references;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.tpe.vopservice.domain.matching.VerificationResult;
import org.junit.jupiter.api.Test;

/** A reference check (specs/13-vop-service.md §3.1) is valid with its result or invalid with its problem. */
class ReferenceCheckTest {

    @Test
    void aValidCheckHasAResultAndNoProblem() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new ReferenceCheck(true, VerificationResult.MATCH, ReferenceProblem.EXPIRED));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReferenceCheck(true, null, null));
    }

    @Test
    void anInvalidCheckHasAProblemAndNoResult() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new ReferenceCheck(false, VerificationResult.MATCH, ReferenceProblem.MISMATCH));
        assertThatIllegalArgumentException().isThrownBy(() -> new ReferenceCheck(false, null, null));
    }
}
