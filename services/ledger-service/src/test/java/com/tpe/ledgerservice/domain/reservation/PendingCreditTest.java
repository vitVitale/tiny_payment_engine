package com.tpe.ledgerservice.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pending credits as memos (ADR-005) and their tombstones (ADR-009, specs/12-ledger-service.md §3.1). */
class PendingCreditTest {

    private static final Instant AT = Instant.parse("2026-01-05T08:00:00Z");
    private static final Instant LATER = AT.plusSeconds(30);
    private static final UUID ID = UUID.fromString("0192f0e4-0000-7000-8000-0000000000a1");
    private static final UUID ACCOUNT = UUID.fromString("0192f0e4-0000-7000-8000-0000000000b1");
    private static final UUID ENTRY = UUID.fromString("0192f0e4-0000-7000-8000-0000000000d1");
    private static final IncomingReference REFERENCE = new IncomingReference(ID, "PEER-TX-1", "PEERDEFFXXX");

    @Test
    void aNewPendingCreditIsActiveWithItsAccountAmountAndReference() {
        PendingCredit credit = PendingCredit.active(ID, ACCOUNT, 5_000, REFERENCE, AT);

        assertThat(credit.status()).isEqualTo(PendingCreditStatus.ACTIVE);
        assertThat(credit.accountId()).isEqualTo(ACCOUNT);
        assertThat(credit.amountMinor()).isEqualTo(5_000);
        assertThat(credit.reference()).isEqualTo(REFERENCE);
        assertThat(credit.tombstone()).isFalse();
        assertThat(credit.finalisedAt()).isNull();
        assertThat(credit.entryId()).isNull();
    }

    @Test
    void aPendingCreditAmountMustBePositive() {
        assertThatIllegalArgumentException().isThrownBy(() -> PendingCredit.active(ID, ACCOUNT, 0, REFERENCE, AT));
    }

    @Test
    void aTombstoneIsCancelledWithoutAccountAmountOrReference() {
        PendingCredit tombstone = PendingCredit.tombstone(ID, AT);

        assertThat(tombstone.status()).isEqualTo(PendingCreditStatus.CANCELLED);
        assertThat(tombstone.tombstone()).isTrue();
        assertThat(tombstone.accountId()).isNull();
        assertThat(tombstone.amountMinor()).isNull();
        assertThat(tombstone.reference()).isNull();
        assertThat(tombstone.finalisedAt()).isEqualTo(AT);
    }

    @Test
    void cancellingAnActiveCreditFinalisesItAndAgainChangesNothing() {
        PendingCredit cancelled = PendingCredit.active(ID, ACCOUNT, 100, REFERENCE, AT).cancel(LATER);

        assertThat(cancelled.status()).isEqualTo(PendingCreditStatus.CANCELLED);
        assertThat(cancelled.finalisedAt()).isEqualTo(LATER);
        assertThat(cancelled.amountMinor()).isEqualTo(100);
        assertThat(cancelled.cancel(LATER.plusSeconds(1))).isSameAs(cancelled);
    }

    @Test
    void aConfirmedCreditCannotBeCancelled() {
        PendingCredit confirmed = PendingCredit.active(ID, ACCOUNT, 100, REFERENCE, AT).confirm(ENTRY, LATER);

        assertRefused(() -> confirmed.cancel(LATER), LedgerError.PENDING_CREDIT_CONFIRMED);
    }

    @Test
    void confirmingAnActiveCreditRecordsItsEntryAndAgainReturnsItAsItIs() {
        PendingCredit confirmed = PendingCredit.active(ID, ACCOUNT, 100, REFERENCE, AT).confirm(ENTRY, LATER);

        assertThat(confirmed.status()).isEqualTo(PendingCreditStatus.CONFIRMED);
        assertThat(confirmed.entryId()).isEqualTo(ENTRY);
        assertThat(confirmed.finalisedAt()).isEqualTo(LATER);
        assertThat(confirmed.confirm(UUID.randomUUID(), LATER.plusSeconds(1))).isSameAs(confirmed);
    }

    @Test
    void aCancelledCreditOrTombstoneCannotBeConfirmed() {
        assertRefused(() -> PendingCredit.active(ID, ACCOUNT, 100, REFERENCE, AT).cancel(LATER).confirm(ENTRY, LATER),
                LedgerError.PENDING_CREDIT_CANCELLED);
        assertRefused(() -> PendingCredit.tombstone(ID, AT).confirm(ENTRY, LATER),
                LedgerError.PENDING_CREDIT_CANCELLED);
    }

    @Test
    void aCreateRetriedWithTheSameAccountAndAmountReplaysTheActiveCredit() {
        PendingCredit credit = PendingCredit.active(ID, ACCOUNT, 100, REFERENCE, AT);

        assertThat(credit.replayFor(ACCOUNT, 100)).isSameAs(credit);
    }

    @Test
    void aCreateOfAnIdUsedForAnotherAccountOrAmountIsAnIdempotencyConflict() {
        PendingCredit credit = PendingCredit.active(ID, ACCOUNT, 100, REFERENCE, AT);

        assertRefused(() -> credit.replayFor(ACCOUNT, 101), LedgerError.IDEMPOTENCY_CONFLICT);
        assertRefused(() -> credit.replayFor(UUID.randomUUID(), 100), LedgerError.IDEMPOTENCY_CONFLICT);
        assertRefused(() -> credit.replayFor(null, 100), LedgerError.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void aCreateOfACancelledTombstonedOrConfirmedIdIsRefused() {
        PendingCredit credit = PendingCredit.active(ID, ACCOUNT, 100, REFERENCE, AT);

        assertRefused(() -> credit.cancel(LATER).replayFor(ACCOUNT, 100), LedgerError.PENDING_CREDIT_CANCELLED);
        assertRefused(() -> PendingCredit.tombstone(ID, AT).replayFor(ACCOUNT, 100),
                LedgerError.PENDING_CREDIT_CANCELLED);
        assertRefused(() -> credit.confirm(ENTRY, LATER).replayFor(ACCOUNT, 100),
                LedgerError.PENDING_CREDIT_CONFIRMED);
    }

    private static void assertRefused(Runnable action, LedgerError error) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LedgerException.class,
                e -> assertThat(e.error()).isEqualTo(error));
    }
}
