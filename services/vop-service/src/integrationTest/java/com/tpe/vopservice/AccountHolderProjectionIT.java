package com.tpe.vopservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.vopservice.application.accounts.AccountHolderProjection;
import com.tpe.vopservice.domain.accounts.AccountHolder;
import com.tpe.vopservice.domain.accounts.AccountHolder.AccountStatus;
import com.tpe.vopservice.domain.accounts.AccountHolder.HolderType;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The account-holder projection (specs/13-vop-service.md §4, §5, ADR-007) at its application-service boundary: newer
 * revisions win, a tombstone deletes the row, the inbox drops a redelivered message. {@code AccountEventsIT} drives the
 * same through Kafka.
 */
class AccountHolderProjectionIT extends VopStack {

    private static final AtomicLong IBANS = new AtomicLong(100_000_000L);

    @Autowired
    AccountHolderProjection projection;

    @Autowired
    JdbcClient jdbc;

    @Test
    void VOP_AC_07_theProjectionAppliesAccountOpenedThenIgnoresAReplayedOlderRevision() {
        AccountHolder opened = person("Dr. Juergen Mueller", AccountStatus.OPEN, 1);
        projection.apply(UUID.randomUUID(), opened);
        assertThat(row(opened.accountId())).contains(new Row("Dr. Juergen Mueller", "juergen mueller", "OPEN", 1));

        projection.apply(UUID.randomUUID(), revision(opened, AccountStatus.BLOCKED, 2));
        // the topic replayed: AccountOpened again, with its own event id, revision 1 and 2 again
        projection.apply(UUID.randomUUID(), opened);
        projection.apply(UUID.randomUUID(), revision(opened, AccountStatus.OPEN, 2));

        assertThat(row(opened.accountId())).contains(new Row("Dr. Juergen Mueller", "juergen mueller", "BLOCKED", 2));
    }

    @Test
    void blockUnblockAndCloseSnapshotsUpdateTheStatus() {
        AccountHolder opened = person("Anna Schmidt", AccountStatus.OPEN, 1);
        projection.apply(UUID.randomUUID(), opened);

        projection.apply(UUID.randomUUID(), revision(opened, AccountStatus.BLOCKED, 2));
        assertThat(row(opened.accountId())).map(Row::status).contains("BLOCKED");
        projection.apply(UUID.randomUUID(), revision(opened, AccountStatus.OPEN, 3));
        assertThat(row(opened.accountId())).map(Row::status).contains("OPEN");
        projection.apply(UUID.randomUUID(), revision(opened, AccountStatus.CLOSED, 4));

        assertThat(row(opened.accountId())).contains(new Row("Anna Schmidt", "anna schmidt", "CLOSED", 4));
    }

    @Test
    void anOrganisationKeepsItsOrganisationIdAndItsNameLosesTheLegalForm() {
        var organisation = new AccountHolder(UUID.randomUUID(), iban(), HolderType.ORGANISATION, "Acme GmbH",
                "529900T8BM49AURSDO55", AccountStatus.OPEN, 1);

        projection.apply(UUID.randomUUID(), organisation);

        assertThat(jdbc.sql("""
                        SELECT holder_type, normalised_name, organisation_id FROM account_holders
                        WHERE account_id = ?""")
                .param(organisation.accountId())
                .query((rs, n) -> rs.getString(1) + "|" + rs.getString(2) + "|" + rs.getString(3)).single())
                .isEqualTo("ORGANISATION|acme|529900T8BM49AURSDO55");
    }

    @Test
    void aTombstoneDeletesTheRow() {
        AccountHolder closed = person("Anna Schmidt", AccountStatus.CLOSED, 2);
        projection.apply(UUID.randomUUID(), closed);

        projection.remove(UUID.randomUUID(), closed.accountId());

        assertThat(row(closed.accountId())).isEmpty();
    }

    @Test
    void aRedeliveredMessageIsDroppedByTheInbox() {
        AccountHolder opened = person("Anna Schmidt", AccountStatus.OPEN, 1);
        UUID openedEvent = UUID.randomUUID();
        projection.apply(openedEvent, opened);
        projection.remove(UUID.randomUUID(), opened.accountId());

        // without the inbox, the late redelivery of AccountOpened would bring the deleted holder back
        projection.apply(openedEvent, opened);

        assertThat(row(opened.accountId())).isEmpty();
        assertThat(jdbc.sql("SELECT count(*) FROM inbox WHERE dedup_key = ?").param(openedEvent.toString())
                .query(Long.class).single()).isEqualTo(1);
    }

    private record Row(String holderName, String normalisedName, String status, long revision) {}

    private Optional<Row> row(UUID accountId) {
        return jdbc.sql("""
                        SELECT holder_name, normalised_name, status, revision FROM account_holders
                        WHERE account_id = ?""")
                .param(accountId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)))
                .optional();
    }

    private static AccountHolder person(String name, AccountStatus status, long revision) {
        return new AccountHolder(UUID.randomUUID(), iban(), HolderType.PERSON, name, null, status, revision);
    }

    private static AccountHolder revision(AccountHolder holder, AccountStatus status, long revision) {
        return new AccountHolder(holder.accountId(), holder.iban(), holder.holderType(), holder.holderName(),
                holder.organisationId(), status, revision);
    }

    /** Unique per test; the projection does not check the format. */
    private static String iban() {
        return "DE00100100100" + IBANS.incrementAndGet();
    }
}
