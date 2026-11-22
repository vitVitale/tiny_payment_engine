package com.tpe.ledgerservice.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.tpe.contracts.TechnicalAccounts;
import com.tpe.paymentdomain.Iban;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * A technical account (ADR-005) has no row balance, no holds and no lifecycle, and is never a payee: every customer
 * operation on it is a programming error, not a business refusal.
 */
class TechnicalAccountTest {

    private static final Instant AT = Instant.parse("2026-03-01T10:00:00Z");
    private static final Account TECHNICAL = new Account(TechnicalAccounts.CSM_SETTLEMENT_ID,
            new Iban("DE89370400440532013000"), AccountKind.TECHNICAL, null, null, AccountStatus.OPEN, List.of(),
            Account.EUR, 0, 0, 0, 0, 1, AT, null);

    @Test
    void itsRowIsNeverBookedAndTakesNoHolds() {
        assertThatIllegalStateException().isThrownBy(() -> TECHNICAL.book(100))
                .withMessageContaining("never booked");
        assertThatIllegalStateException().isThrownBy(() -> TECHNICAL.placeHold(100))
                .withMessageContaining("no holds");
    }

    @Test
    void itIsNeverAPayee() {
        assertThat(TECHNICAL.isPayee()).isFalse();
        assertThatIllegalStateException().isThrownBy(TECHNICAL::requirePayee)
                .withMessageContaining("never a payee");
    }

    @Test
    void itHasNoLifecycle() {
        assertThatIllegalStateException().isThrownBy(() -> TECHNICAL.close(AT))
                .withMessageContaining("no lifecycle");
        assertThatIllegalStateException().isThrownBy(() -> TECHNICAL.block(BlockReason.OPERATOR))
                .withMessageContaining("no lifecycle");
        assertThatIllegalStateException().isThrownBy(() -> TECHNICAL.unblock(BlockReason.OPERATOR))
                .withMessageContaining("no lifecycle");
    }
}
