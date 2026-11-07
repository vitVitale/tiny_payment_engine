package com.tpe.ledgerservice.domain.reservation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Holds as memos (ADR-005) and their tombstones (ADR-009, specs/12-ledger-service.md §3.1). */
class HoldTest {

    private static final Instant AT = Instant.parse("2026-01-05T08:00:00Z");
    private static final Instant LATER = AT.plusSeconds(30);
    private static final UUID HOLD = UUID.fromString("0192f0e4-0000-7000-8000-0000000000a1");
    private static final UUID ACCOUNT = UUID.fromString("0192f0e4-0000-7000-8000-0000000000b1");
    private static final UUID PAYMENT = UUID.fromString("0192f0e4-0000-7000-8000-0000000000c1");
    private static final UUID ENTRY = UUID.fromString("0192f0e4-0000-7000-8000-0000000000d1");

    @Test
    void aNewHoldIsActiveWithItsAccountAndAmount() {
        Hold hold = Hold.active(HOLD, ACCOUNT, 12_345, PAYMENT, "TPE-TX-1", AT);

        assertThat(hold.status()).isEqualTo(HoldStatus.ACTIVE);
        assertThat(hold.accountId()).isEqualTo(ACCOUNT);
        assertThat(hold.amountMinor()).isEqualTo(12_345);
        assertThat(hold.tombstone()).isFalse();
        assertThat(hold.finalisedAt()).isNull();
    }

    @Test
    void aTombstoneIsReleasedWithoutAccountOrAmount() {
        Hold tombstone = Hold.tombstone(HOLD, AT);

        assertThat(tombstone.status()).isEqualTo(HoldStatus.RELEASED);
        assertThat(tombstone.tombstone()).isTrue();
        assertThat(tombstone.accountId()).isNull();
        assertThat(tombstone.amountMinor()).isNull();
        assertThat(tombstone.finalisedAt()).isEqualTo(AT);
    }

    @Test
    void releasingAnActiveHoldFinalisesIt() {
        Hold released = Hold.active(HOLD, ACCOUNT, 100, PAYMENT, null, AT).release(LATER);

        assertThat(released.status()).isEqualTo(HoldStatus.RELEASED);
        assertThat(released.finalisedAt()).isEqualTo(LATER);
        assertThat(released.amountMinor()).isEqualTo(100);
    }

    @Test
    void aCreateRetriedWithTheSameAccountAndAmountReplaysTheActiveHold() {
        Hold hold = Hold.active(HOLD, ACCOUNT, 100, PAYMENT, null, AT);

        assertThat(hold.replayFor(ACCOUNT, 100)).isSameAs(hold);
    }

    @Test
    void aCreateForAnotherAccountOrAmountUnderAnActiveHoldsIdIsAConflict() {
        Hold hold = Hold.active(HOLD, ACCOUNT, 100, PAYMENT, null, AT);

        assertRefused(() -> hold.replayFor(ACCOUNT, 101), LedgerError.IDEMPOTENCY_CONFLICT);
        assertRefused(() -> hold.replayFor(UUID.randomUUID(), 100), LedgerError.IDEMPOTENCY_CONFLICT);
    }

    @Test
    void aCreateOfAReleasedOrTombstonedHoldIsHoldReleased() {
        assertRefused(() -> Hold.tombstone(HOLD, AT).replayFor(ACCOUNT, 100), LedgerError.HOLD_RELEASED);
        assertRefused(() -> Hold.active(HOLD, ACCOUNT, 100, PAYMENT, null, AT).release(LATER).replayFor(ACCOUNT, 100),
                LedgerError.HOLD_RELEASED);
    }

    @Test
    void aCreateOfASettledHoldIsHoldSettled() {
        Hold settled = Hold.active(HOLD, ACCOUNT, 100, PAYMENT, null, AT).settle(ENTRY, LATER);

        assertRefused(() -> settled.replayFor(ACCOUNT, 100), LedgerError.HOLD_SETTLED);
    }

    @Test
    void onlyAnActiveHoldCanBeReleased() {
        Hold settled = Hold.active(HOLD, ACCOUNT, 100, PAYMENT, null, AT).settle(ENTRY, LATER);

        assertRefused(() -> settled.release(LATER), LedgerError.HOLD_SETTLED);
    }

    @Test
    void releasingAReleasedHoldOrATombstoneChangesNothing() {
        Hold released = Hold.active(HOLD, ACCOUNT, 100, PAYMENT, null, AT).release(LATER);
        Hold tombstone = Hold.tombstone(HOLD, AT);

        assertThat(released.release(LATER.plusSeconds(5))).isSameAs(released);
        assertThat(tombstone.release(LATER)).isSameAs(tombstone);
        assertThat(released.active()).isFalse();
        assertThat(tombstone.active()).isFalse();
    }

    @Test
    void settlingAnActiveHoldFinalisesItWithItsJournalEntry() {
        Hold settled = Hold.active(HOLD, ACCOUNT, 100, PAYMENT, "TPE-TX-1", AT).settle(ENTRY, LATER);

        assertThat(settled.status()).isEqualTo(HoldStatus.SETTLED);
        assertThat(settled.entryId()).isEqualTo(ENTRY);
        assertThat(settled.finalisedAt()).isEqualTo(LATER);
        assertThat(settled.amountMinor()).isEqualTo(100);
        assertThat(settled.accountId()).isEqualTo(ACCOUNT);
        assertThat(settled.active()).isFalse();
    }

    @Test
    void settlingASettledHoldChangesNothing() {
        Hold settled = Hold.active(HOLD, ACCOUNT, 100, PAYMENT, null, AT).settle(ENTRY, LATER);

        assertThat(settled.settle(UUID.randomUUID(), LATER.plusSeconds(5))).isSameAs(settled);
    }

    @Test
    void aReleasedOrTombstonedHoldCannotBeSettled() {
        assertRefused(() -> Hold.tombstone(HOLD, AT).settle(ENTRY, LATER), LedgerError.HOLD_RELEASED);
        assertRefused(() -> Hold.active(HOLD, ACCOUNT, 100, PAYMENT, null, AT).release(LATER).settle(ENTRY, LATER),
                LedgerError.HOLD_RELEASED);
    }

    @Test
    void anActiveOrReleasedHoldHasNoJournalEntry() {
        Hold hold = Hold.active(HOLD, ACCOUNT, 100, PAYMENT, null, AT);

        assertThat(hold.entryId()).isNull();
        assertThat(hold.release(LATER).entryId()).isNull();
        assertThat(Hold.tombstone(HOLD, AT).entryId()).isNull();
    }

    @Test
    void anActiveHoldNeedsAnAccountAndAPositiveAmount() {
        assertThatIllegalArgumentException().isThrownBy(() -> Hold.active(HOLD, ACCOUNT, 0, PAYMENT, null, AT));
        assertThatThrownBy(() -> Hold.active(HOLD, null, 1, PAYMENT, null, AT))
                .isInstanceOf(NullPointerException.class);
    }

    private static void assertRefused(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, LedgerError error) {
        assertThatThrownBy(call).isInstanceOfSatisfying(LedgerException.class,
                e -> assertThat(e.error()).isEqualTo(error));
    }
}
