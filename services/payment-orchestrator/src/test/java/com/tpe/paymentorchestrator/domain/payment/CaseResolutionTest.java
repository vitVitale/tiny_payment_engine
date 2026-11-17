package com.tpe.paymentorchestrator.domain.payment;

import static com.tpe.paymentorchestrator.domain.payment.CaseResolution.ACKNOWLEDGED;
import static com.tpe.paymentorchestrator.domain.payment.CaseResolution.CONFIRMED_REJECTED;
import static com.tpe.paymentorchestrator.domain.payment.CaseResolution.CONFIRMED_SETTLED;
import static com.tpe.paymentorchestrator.domain.payment.CaseResolution.RETRY_INVESTIGATION;
import static com.tpe.paymentorchestrator.domain.payment.CaseResolution.RETRY_NOW;
import static com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType.CONTRADICTING_CSM_OUTCOME;
import static com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType.DLT_MESSAGE;
import static com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType.LEDGER_ACTION_STUCK;
import static com.tpe.paymentorchestrator.domain.payment.ExceptionCaseType.UNRESOLVED_INVESTIGATION;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/** The resolutions an operator may give each case type (specs/11-payment-orchestrator.md §3.2). */
class CaseResolutionTest {

    @Test
    void anUnresolvedInvestigationIsRetriedOrConfirmedEitherWay() {
        assertThat(allowed(UNRESOLVED_INVESTIGATION))
                .containsExactlyInAnyOrder(RETRY_INVESTIGATION, CONFIRMED_SETTLED, CONFIRMED_REJECTED);
    }

    @Test
    void aContradictionAndADeadLetterAreOnlyAcknowledged() {
        assertThat(allowed(CONTRADICTING_CSM_OUTCOME)).containsExactly(ACKNOWLEDGED);
        assertThat(allowed(DLT_MESSAGE)).containsExactly(ACKNOWLEDGED);
    }

    @Test
    void aStuckLedgerActionIsRetriedNow() {
        assertThat(allowed(LEDGER_ACTION_STUCK)).containsExactly(RETRY_NOW);
    }

    @Test
    void onlyARejectionNeedsAReasonCode() {
        assertThat(EnumSet.allOf(CaseResolution.class)).filteredOn(CaseResolution::needsReasonCode)
                .containsExactly(CONFIRMED_REJECTED);
    }

    private static EnumSet<CaseResolution> allowed(ExceptionCaseType type) {
        EnumSet<CaseResolution> allowed = EnumSet.noneOf(CaseResolution.class);
        for (CaseResolution resolution : CaseResolution.values()) {
            if (resolution.resolves(type)) {
                allowed.add(resolution);
            }
        }
        return allowed;
    }
}
