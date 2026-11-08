package com.tpe.ledgerservice.domain.posting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.tpe.contracts.TechnicalAccounts;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** A technical account's balance from snapshots and the postings after them (specs/12-ledger-service.md §6.3). */
class BalanceSnapshotTest {

    private static final UUID CSM = TechnicalAccounts.CSM_SETTLEMENT_ID;
    private static final Instant AT = Instant.parse("2026-01-05T08:15:00Z");

    @Test
    void beforeTheFirstPostingTheSnapshotHasBoundaryAndBalanceZero() {
        BalanceSnapshot none = BalanceSnapshot.none(CSM);

        assertThat(none.upToPostingId()).isZero();
        assertThat(none.balanceMinor()).isZero();
    }

    @Test
    void theNextSnapshotAddsThePostingsSinceThePreviousBoundary() {
        BalanceSnapshot first = BalanceSnapshot.none(CSM).next(120, 5_000, AT);
        BalanceSnapshot next = first.next(300, -1_200, AT.plusSeconds(900));

        assertThat(first.balanceMinor()).isEqualTo(5_000);
        assertThat(next.upToPostingId()).isEqualTo(300);
        assertThat(next.balanceMinor()).isEqualTo(3_800);
        assertThat(next.takenAt()).isEqualTo(AT.plusSeconds(900));
    }

    @Test
    void aNextSnapshotMustMoveTheBoundaryForward() {
        BalanceSnapshot first = BalanceSnapshot.none(CSM).next(120, 5_000, AT);

        assertThatIllegalArgumentException().isThrownBy(() -> first.next(120, 0, AT));
    }

    @Test
    void aConfirmedSnapshotKeepsItsBoundaryAndBalance() {
        BalanceSnapshot first = BalanceSnapshot.none(CSM).next(120, 5_000, AT);

        BalanceSnapshot confirmed = first.confirmedAt(AT.plusSeconds(900));

        assertThat(confirmed.upToPostingId()).isEqualTo(120);
        assertThat(confirmed.balanceMinor()).isEqualTo(5_000);
        assertThat(confirmed.takenAt()).isEqualTo(AT.plusSeconds(900));
    }
}
