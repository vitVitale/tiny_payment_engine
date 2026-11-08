package com.tpe.ledgerservice.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Closing a customer account (specs/12-ledger-service.md §3.2, UC-10): only an empty one, once. */
class AccountCloseTest {

    private static final Instant OPENED = Instant.parse("2026-01-05T08:00:00Z");
    private static final Instant AT = Instant.parse("2026-03-01T10:00:00Z");

    @Test
    void anEmptyAccountClosesWithItsCloseTimeAndTheNextRevision() {
        Account account = account(AccountStatus.OPEN, 0, 0, 0);

        Account closed = account.close(AT);

        assertThat(closed.status()).isEqualTo(AccountStatus.CLOSED);
        assertThat(closed.closedAt()).isEqualTo(AT);
        assertThat(closed.revision()).isEqualTo(account.revision() + 1);
        assertThat(closed.version()).isEqualTo(account.version());
    }

    @Test
    void anEmptyAccountWithOnlyAnOperatorBlockClosesKeepingTheReason() {
        Account closed = account(List.of(BlockReason.OPERATOR), 0, 0, 0).close(AT);

        assertThat(closed.status()).isEqualTo(AccountStatus.CLOSED);
        assertThat(closed.activeBlockReasons()).containsExactly(BlockReason.OPERATOR);
    }

    @Test
    void anActiveSanctionsBlockKeepsAnEmptyAccountOpen() {
        assertRefused(() -> account(List.of(BlockReason.SANCTIONS), 0, 0, 0).close(AT), LedgerError.ACCOUNT_BLOCKED);
        assertRefused(() -> account(List.of(BlockReason.SANCTIONS_PROVISIONAL, BlockReason.OPERATOR), 0, 0, 0)
                .close(AT), LedgerError.ACCOUNT_BLOCKED);
    }

    @Test
    void aBalanceAnActiveHoldOrAnActivePendingCreditKeepsTheAccountOpen() {
        assertRefused(() -> account(AccountStatus.OPEN, 1, 0, 0).close(AT), LedgerError.ACCOUNT_NOT_EMPTY);
        assertRefused(() -> account(AccountStatus.OPEN, 500, 500, 0).close(AT), LedgerError.ACCOUNT_NOT_EMPTY);
        assertRefused(() -> account(AccountStatus.OPEN, 0, 0, 300).close(AT), LedgerError.ACCOUNT_NOT_EMPTY);
    }

    @Test
    void aClosedAccountCannotBeClosedAgain() {
        assertRefused(() -> account(AccountStatus.OPEN, 0, 0, 0).close(AT).close(AT.plusSeconds(1)),
                LedgerError.ACCOUNT_CLOSED);
    }

    private static Account account(AccountStatus status, long booked, long held, long pendingCredits) {
        return account(status == AccountStatus.BLOCKED ? List.of(BlockReason.OPERATOR) : List.of(), booked, held,
                pendingCredits);
    }

    /** {@code BLOCKED} with these reasons, {@code OPEN} without any. */
    private static Account account(List<BlockReason> blocks, long booked, long held, long pendingCredits) {
        return new Account(UUID.randomUUID(), new IbanGenerator("10010010").generate(11), AccountKind.CUSTOMER,
                new CustomerId("C1"), Holder.of(HolderType.PERSON, "Anna Schmidt", null, null, null),
                blocks.isEmpty() ? AccountStatus.OPEN : AccountStatus.BLOCKED, blocks, Account.EUR, booked, held,
                pendingCredits, 4, 3, OPENED, null);
    }

    private static void assertRefused(Runnable action, LedgerError error) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(LedgerException.class,
                e -> assertThat(e.error()).isEqualTo(error));
    }
}
