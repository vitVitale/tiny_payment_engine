package com.tpe.ledgerservice.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Blocks per reason (specs/12-ledger-service.md §3.2): {@code BLOCKED} while at least one is active. */
class AccountBlockTest {

    private static final Instant OPENED = Instant.parse("2026-01-05T08:00:00Z");
    private static final Instant CLOSED_AT = Instant.parse("2026-03-01T08:00:00Z");

    @Test
    void aFirstBlockMakesTheAccountBlockedAndBumpsTheRevision() {
        Account open = account(AccountStatus.OPEN, List.of());

        BlockChange change = open.block(BlockReason.OPERATOR);

        assertThat(change.changed()).isTrue();
        assertThat(change.replaced()).isNull();
        assertThat(change.account().status()).isEqualTo(AccountStatus.BLOCKED);
        assertThat(change.account().activeBlockReasons()).containsExactly(BlockReason.OPERATOR);
        assertThat(change.account().revision()).isEqualTo(open.revision() + 1);
        // the repository bumps the version when it writes the row
        assertThat(change.account().version()).isEqualTo(open.version());
    }

    @Test
    void blocksAreKeptPerReason() {
        Account blocked = account(AccountStatus.BLOCKED, List.of(BlockReason.OPERATOR));

        BlockChange change = blocked.block(BlockReason.SANCTIONS_PROVISIONAL);

        assertThat(change.account().activeBlockReasons())
                .containsExactlyInAnyOrder(BlockReason.OPERATOR, BlockReason.SANCTIONS_PROVISIONAL);
        assertThat(change.account().revision()).isEqualTo(blocked.revision() + 1);
    }

    @Test
    void blockingWithAReasonAlreadyActiveChangesNothing() {
        Account blocked = account(AccountStatus.BLOCKED, List.of(BlockReason.OPERATOR));

        BlockChange change = blocked.block(BlockReason.OPERATOR);

        assertThat(change.changed()).isFalse();
        assertThat(change.account()).isEqualTo(blocked);
    }

    @Test
    void sanctionsReplacesAnActiveProvisionalSanctionsBlock() {
        Account provisional = account(AccountStatus.BLOCKED, List.of(BlockReason.SANCTIONS_PROVISIONAL));

        BlockChange change = provisional.block(BlockReason.SANCTIONS);

        assertThat(change.replaced()).isEqualTo(BlockReason.SANCTIONS_PROVISIONAL);
        assertThat(change.account().activeBlockReasons()).containsExactly(BlockReason.SANCTIONS);
        // one lifecycle change
        assertThat(change.account().revision()).isEqualTo(provisional.revision() + 1);
    }

    @Test
    void liftingTheLastBlockOpensTheAccount() {
        Account blocked = account(AccountStatus.BLOCKED, List.of(BlockReason.OPERATOR));

        BlockChange change = blocked.unblock(BlockReason.OPERATOR);

        assertThat(change.changed()).isTrue();
        assertThat(change.account().status()).isEqualTo(AccountStatus.OPEN);
        assertThat(change.account().activeBlockReasons()).isEmpty();
        assertThat(change.account().revision()).isEqualTo(blocked.revision() + 1);
    }

    @Test
    void liftingOneOfTwoBlocksLeavesTheAccountBlocked() {
        Account blocked = account(AccountStatus.BLOCKED, List.of(BlockReason.OPERATOR, BlockReason.SANCTIONS));

        BlockChange change = blocked.unblock(BlockReason.OPERATOR);

        assertThat(change.account().status()).isEqualTo(AccountStatus.BLOCKED);
        assertThat(change.account().activeBlockReasons()).containsExactly(BlockReason.SANCTIONS);
        assertThat(change.account().revision()).isEqualTo(blocked.revision() + 1);
    }

    @Test
    void liftingAReasonThatIsNotActiveChangesNothing() {
        Account blocked = account(AccountStatus.BLOCKED, List.of(BlockReason.OPERATOR));

        BlockChange change = blocked.unblock(BlockReason.SANCTIONS);

        assertThat(change.changed()).isFalse();
        assertThat(change.account()).isEqualTo(blocked);
    }

    @Test
    void aClosedAccountCannotBeBlockedOrUnblocked() {
        Account closed = account(AccountStatus.CLOSED, List.of());

        assertThatThrownBy(() -> closed.block(BlockReason.OPERATOR))
                .isInstanceOfSatisfying(LedgerException.class,
                        e -> assertThat(e.error()).isEqualTo(LedgerError.ACCOUNT_CLOSED));
        assertThatThrownBy(() -> closed.unblock(BlockReason.OPERATOR))
                .isInstanceOfSatisfying(LedgerException.class,
                        e -> assertThat(e.error()).isEqualTo(LedgerError.ACCOUNT_CLOSED));
    }

    @Test
    void activeBlockReasonsAreInTheirDeclarationOrderWithoutDuplicates() {
        Account account = account(AccountStatus.BLOCKED,
                List.of(BlockReason.OPERATOR, BlockReason.SANCTIONS_PROVISIONAL, BlockReason.OPERATOR));

        assertThat(account.activeBlockReasons())
                .containsExactly(BlockReason.SANCTIONS_PROVISIONAL, BlockReason.OPERATOR);
    }

    @Test
    void onlySanctionsReasonsAreSanctions() {
        assertThat(BlockReason.SANCTIONS.sanctions()).isTrue();
        assertThat(BlockReason.SANCTIONS_PROVISIONAL.sanctions()).isTrue();
        assertThat(BlockReason.OPERATOR.sanctions()).isFalse();
    }

    private static Account account(AccountStatus status, List<BlockReason> blocks) {
        return new Account(UUID.randomUUID(), new IbanGenerator("10010010").generate(7), AccountKind.CUSTOMER,
                new CustomerId("CUST-1"), Holder.of(HolderType.PERSON, "Anna Schmidt", null, null, null), status,
                blocks, Account.EUR, 0, 0, 0, 4, 3, OPENED, status == AccountStatus.CLOSED ? CLOSED_AT : null);
    }
}
