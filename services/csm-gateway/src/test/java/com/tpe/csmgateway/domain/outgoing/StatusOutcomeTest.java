package com.tpe.csmgateway.domain.outgoing;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.csmgateway.domain.outgoing.StatusOutcome.Effect;
import com.tpe.csmgateway.domain.outgoing.OutgoingTransfer.SendStatus;
import com.tpe.paymentdomain.ReasonCode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class StatusOutcomeTest {

    @Test
    void theFirstFinalStatusIsRecorded() {
        assertThat(StatusOutcome.of(null, ReportedStatus.settled())).isEqualTo(Effect.RECORD);
        assertThat(StatusOutcome.of(null, ReportedStatus.rejected(ReasonCode.AM04))).isEqualTo(Effect.RECORD);
    }

    @Test
    void theSameFinalStatusAgainIsADuplicate() {
        assertThat(StatusOutcome.of(FinalStatus.SETTLED, ReportedStatus.settled())).isEqualTo(Effect.DUPLICATE);
        assertThat(StatusOutcome.of(FinalStatus.REJECTED, ReportedStatus.rejected(ReasonCode.AM04)))
                .isEqualTo(Effect.DUPLICATE);
    }

    @Test
    void aRejectionWithAnotherReasonIsStillTheSameStatus() {
        assertThat(StatusOutcome.of(FinalStatus.REJECTED, ReportedStatus.rejected(ReasonCode.AC04)))
                .isEqualTo(Effect.DUPLICATE);
    }

    @Test
    void anotherFinalStatusContradictsTheRecordedOne() {
        assertThat(StatusOutcome.of(FinalStatus.SETTLED, ReportedStatus.rejected(ReasonCode.AB05)))
                .isEqualTo(Effect.CONTRADICTION);
        assertThat(StatusOutcome.of(FinalStatus.REJECTED, ReportedStatus.settled())).isEqualTo(Effect.CONTRADICTION);
    }

    @Test
    void acceptedIsInformationalWhateverIsRecorded() {
        assertThat(StatusOutcome.of(null, ReportedStatus.accepted())).isEqualTo(Effect.INFORMATIONAL);
        assertThat(StatusOutcome.of(FinalStatus.SETTLED, ReportedStatus.accepted())).isEqualTo(Effect.INFORMATIONAL);
    }

    @Test
    void anUnknownTransactionAnswerIsLeftToTheInvestigation() {
        assertThat(StatusOutcome.of(null, ReportedStatus.unknownTransaction())).isEqualTo(Effect.INFORMATIONAL);
    }

    @Test
    void readsTheStatusOfAStatusReport() {
        assertThat(ReportedStatus.of("ACSC", null, null)).isEqualTo(ReportedStatus.settled());
        assertThat(ReportedStatus.of("ACCP", null, null)).isEqualTo(ReportedStatus.accepted());
        assertThat(ReportedStatus.of("RJCT", "AM04", null)).isEqualTo(ReportedStatus.rejected(ReasonCode.AM04));
        assertThat(ReportedStatus.of("RJCT", null, "UNKNOWN_TX")).isEqualTo(ReportedStatus.unknownTransaction());
    }

    @Test
    void aStatusReportItCannotReadIsUnknown() {
        assertThat(ReportedStatus.of("PDNG", null, null)).isEqualTo(ReportedStatus.unreadable());
        assertThat(ReportedStatus.of("RJCT", null, null)).isEqualTo(ReportedStatus.unreadable());
        assertThat(ReportedStatus.of("RJCT", null, "SOMETHING_ELSE")).isEqualTo(ReportedStatus.unreadable());
        assertThat(StatusOutcome.of(null, ReportedStatus.unreadable())).isEqualTo(Effect.INFORMATIONAL);
    }

    private static final Instant DEADLINE = Instant.parse("2026-11-15T10:00:20Z");

    @Test
    void GW_AC_07_unknownTxForASentTransferAtOrAfterItsDeadlineIsARejectionAb05() {
        assertThat(StatusOutcome.effective(ReportedStatus.unknownTransaction(), null, SendStatus.SENT, DEADLINE, DEADLINE))
                .isEqualTo(ReportedStatus.rejected(ReasonCode.AB05));
        assertThat(StatusOutcome.effective(ReportedStatus.unknownTransaction(), null, SendStatus.SENT, DEADLINE,
                DEADLINE.plusSeconds(60))).isEqualTo(ReportedStatus.rejected(ReasonCode.AB05));
    }

    @Test
    void GW_AC_07_unknownTxBeforeTheDeadlineStaysUnknown() {
        assertThat(StatusOutcome.effective(ReportedStatus.unknownTransaction(), null, SendStatus.SENT, DEADLINE,
                DEADLINE.minusMillis(1))).isEqualTo(ReportedStatus.unknownTransaction());
    }

    @Test
    void unknownTxForATransferNotSentStaysUnknown() {
        assertThat(StatusOutcome.effective(ReportedStatus.unknownTransaction(), null, SendStatus.NOT_SENT, DEADLINE,
                DEADLINE.plusSeconds(60))).isEqualTo(ReportedStatus.unknownTransaction());
    }

    @Test
    void anyOtherStatusIsAsReported() {
        assertThat(StatusOutcome.effective(ReportedStatus.settled(), null, SendStatus.SENT, DEADLINE, DEADLINE))
                .isEqualTo(ReportedStatus.settled());
        assertThat(StatusOutcome.effective(ReportedStatus.accepted(), null, SendStatus.SENT, DEADLINE, DEADLINE))
                .isEqualTo(ReportedStatus.accepted());
    }

    @Test
    void unknownTxAfterAFinalStatusWasRecordedStaysUnknown() {
        assertThat(StatusOutcome.effective(ReportedStatus.unknownTransaction(), FinalStatus.SETTLED, SendStatus.SENT,
                DEADLINE, DEADLINE.plusSeconds(60))).isEqualTo(ReportedStatus.unknownTransaction());
    }
}
