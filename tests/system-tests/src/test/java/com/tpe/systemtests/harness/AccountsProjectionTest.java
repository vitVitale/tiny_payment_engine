package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.contracts.AccountBlocked;
import com.tpe.contracts.AccountClosed;
import com.tpe.contracts.AccountEvent;
import com.tpe.contracts.AccountKind;
import com.tpe.contracts.AccountOpened;
import com.tpe.contracts.AccountSnapshot;
import com.tpe.contracts.AccountStatus;
import com.tpe.contracts.BlockReason;
import com.tpe.contracts.Holder;
import com.tpe.contracts.HolderType;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.PartyName;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountsProjectionTest {

    private static final Instant OPENED = Instant.parse("2026-11-08T10:00:00Z");
    private final AccountsProjection projection = new AccountsProjection();
    private final UUID id = UUID.randomUUID();

    @Test
    void aNewerRevisionReplacesTheStoredAccount() {
        projection.apply(id, opened(1));
        projection.apply(id, blocked(2));

        assertThat(projection.account(id)).hasValueSatisfying(account -> {
            assertThat(account.revision()).isEqualTo(2);
            assertThat(account.snapshot().status()).isEqualTo(AccountStatus.BLOCKED);
        });
    }

    @Test
    void anEventNotNewerThanTheStoredRevisionIsIgnoredButStillRecordedAsReceived() {
        projection.apply(id, blocked(2));
        projection.apply(id, opened(1));
        projection.apply(id, blocked(2));

        assertThat(projection.account(id)).hasValueSatisfying(account -> {
            assertThat(account.revision()).isEqualTo(2);
            assertThat(account.snapshot().status()).isEqualTo(AccountStatus.BLOCKED);
        });
        assertThat(projection.received(id)).extracting(AccountsProjection.Received::revision).containsExactly(2L, 1L, 2L);
    }

    @Test
    void aTombstoneDeletesTheAccount() {
        projection.apply(id, opened(1));
        projection.apply(id, closed(2));

        projection.apply(id, null);

        assertThat(projection.account(id)).isEmpty();
        assertThat(projection.received(id)).extracting(AccountsProjection.Received::type)
                .containsExactly("AccountOpened", "AccountClosed", AccountsProjection.TOMBSTONE);
    }

    @Test
    void accountsAreKeptApart() {
        UUID other = UUID.randomUUID();
        projection.apply(id, opened(1));

        assertThat(projection.account(other)).isEmpty();
        assertThat(projection.received(other)).isEmpty();
    }

    private AccountOpened opened(long revision) {
        return new AccountOpened(id, revision, OPENED, snapshot(AccountStatus.OPEN, List.of()));
    }

    private AccountBlocked blocked(long revision) {
        return new AccountBlocked(id, revision, OPENED.plusSeconds(revision),
                snapshot(AccountStatus.BLOCKED, List.of(BlockReason.OPERATOR)), BlockReason.OPERATOR);
    }

    private AccountEvent closed(long revision) {
        return new AccountClosed(id, revision, OPENED.plusSeconds(revision), snapshot(AccountStatus.CLOSED, List.of()));
    }

    private static AccountSnapshot snapshot(AccountStatus status, List<BlockReason> reasons) {
        return new AccountSnapshot(new Iban("DE89370400440532013000"), "CUST-1", status, reasons, AccountKind.CUSTOMER,
                new Holder(HolderType.PERSON, new PartyName("Anna Schmidt"), null, null, null), OPENED,
                status == AccountStatus.CLOSED ? OPENED.plusSeconds(60) : null);
    }
}
