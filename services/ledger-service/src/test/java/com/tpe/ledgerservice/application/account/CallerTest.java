package com.tpe.ledgerservice.application.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import com.tpe.ledgerservice.domain.account.BlockReason;
import org.junit.jupiter.api.Test;

/** Who may change blocks of which reason (specs/12-ledger-service.md §3.2, ADR-012). */
class CallerTest {

    private static final Caller OPERATOR = new Caller("op-1", true, false);
    private static final Caller COMPLIANCE = new Caller("officer-1", false, true);
    private static final Caller RISK_SERVICE = new Caller(Caller.RISK_SERVICE, false, true);

    @Test
    void aBackofficeOperatorChangesOperatorBlocksButNotSanctionsOnes() {
        assertThatCode(() -> OPERATOR.requireMayChange(BlockReason.OPERATOR)).doesNotThrowAnyException();
        assertRefused(OPERATOR, BlockReason.SANCTIONS, LedgerError.SANCTIONS_BLOCK_PROTECTED);
        assertRefused(OPERATOR, BlockReason.SANCTIONS_PROVISIONAL, LedgerError.SANCTIONS_BLOCK_PROTECTED);
    }

    @Test
    void complianceOfficersAndRiskServiceChangeSanctionsBlocksOnly() {
        for (Caller caller : new Caller[] {COMPLIANCE, RISK_SERVICE}) {
            assertThatCode(() -> caller.requireMayChange(BlockReason.SANCTIONS)).doesNotThrowAnyException();
            assertThatCode(() -> caller.requireMayChange(BlockReason.SANCTIONS_PROVISIONAL))
                    .doesNotThrowAnyException();
            assertRefused(caller, BlockReason.OPERATOR, LedgerError.FORBIDDEN);
        }
    }

    @Test
    void anOperatorWhoIsAlsoAComplianceOfficerChangesBoth() {
        Caller both = new Caller("op-2", true, true);

        assertThatCode(() -> both.requireMayChange(BlockReason.OPERATOR)).doesNotThrowAnyException();
        assertThatCode(() -> both.requireMayChange(BlockReason.SANCTIONS)).doesNotThrowAnyException();
    }

    private static void assertRefused(Caller caller, BlockReason reason, LedgerError error) {
        assertThatThrownBy(() -> caller.requireMayChange(reason))
                .isInstanceOfSatisfying(LedgerException.class, e -> assertThat(e.error()).isEqualTo(error));
    }
}
