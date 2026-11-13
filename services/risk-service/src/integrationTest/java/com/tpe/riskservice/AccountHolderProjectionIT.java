package com.tpe.riskservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.riskservice.application.accounts.AccountHolderProjection;
import com.tpe.riskservice.domain.accounts.AccountHolder;
import com.tpe.riskservice.domain.accounts.AccountHolder.AccountStatus;
import com.tpe.riskservice.domain.accounts.AccountHolder.HolderType;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The account-holder projection (specs/14-risk-service.md §4, §5, ADR-007) at its application-service boundary: newer
 * revisions win, a tombstone deletes the row, the inbox drops a redelivered message. {@code AccountEventsIT} drives the
 * same through Kafka.
 */
class AccountHolderProjectionIT extends RiskStack {

    private static final Instant OPENED_AT = Instant.parse("2026-11-02T09:15:00.123Z");

    @Autowired
    AccountHolderProjection projection;

    @Autowired
    JdbcClient jdbc;

    @Test
    void theProjectionStoresTheSnapshotThenIgnoresAReplayedOlderRevision() {
        AccountHolder opened = person(AccountStatus.OPEN, 1);
        projection.apply(UUID.randomUUID(), opened);
        assertThat(row(opened.accountId())).contains(new Row("OPEN", 1));

        projection.apply(UUID.randomUUID(), revision(opened, AccountStatus.BLOCKED, 2));
        // the topic replayed: AccountOpened again, with its own event id, revision 1 and 2 again
        projection.apply(UUID.randomUUID(), opened);
        projection.apply(UUID.randomUUID(), revision(opened, AccountStatus.OPEN, 2));

        assertThat(row(opened.accountId())).contains(new Row("BLOCKED", 2));
    }

    @Test
    void theRowHoldsWhatTheRulesAndScreeningRead() {
        AccountHolder opened = person(AccountStatus.OPEN, 1);

        projection.apply(UUID.randomUUID(), opened);

        assertThat(jdbc.sql("""
                        SELECT customer_id || '|' || iban || '|' || holder_type || '|' || holder_name || '|'
                               || date_of_birth || '|' || country || '|' || (opened_at = ?)
                        FROM account_holders WHERE account_id = ?""")
                .param(Timestamp.from(OPENED_AT)).param(opened.accountId()).query(String.class).single())
                .isEqualTo("CUST-000123|" + opened.iban() + "|PERSON|Anna Schmidt|1985-04-12|DE|true");
    }

    @Test
    void anOrganisationKeepsItsOrganisationId() {
        var organisation = new AccountHolder(UUID.randomUUID(), "CUST-000124", Ibans.fresh(),
                HolderType.ORGANISATION, "ACME GmbH", null, "DE", "529900T8BM49AURSDO55", AccountStatus.OPEN,
                OPENED_AT, 1);

        projection.apply(UUID.randomUUID(), organisation);

        assertThat(jdbc.sql("SELECT holder_type || '|' || organisation_id FROM account_holders WHERE account_id = ?")
                .param(organisation.accountId()).query(String.class).single())
                .isEqualTo("ORGANISATION|529900T8BM49AURSDO55");
    }

    @Test
    void aTombstoneDeletesTheHolder() {
        AccountHolder closed = person(AccountStatus.CLOSED, 2);
        projection.apply(UUID.randomUUID(), closed);

        projection.remove(UUID.randomUUID(), closed.accountId());

        assertThat(row(closed.accountId())).isEmpty();
    }

    @Test
    void aRedeliveredMessageIsDroppedByTheInbox() {
        AccountHolder opened = person(AccountStatus.OPEN, 1);
        UUID openedEvent = UUID.randomUUID();
        projection.apply(openedEvent, opened);
        projection.remove(UUID.randomUUID(), opened.accountId());

        // without the inbox, the late redelivery of AccountOpened would bring the deleted holder back
        projection.apply(openedEvent, opened);

        assertThat(row(opened.accountId())).isEmpty();
    }

    private record Row(String status, long revision) {}

    private Optional<Row> row(UUID accountId) {
        return jdbc.sql("SELECT status, revision FROM account_holders WHERE account_id = ?")
                .param(accountId)
                .query((rs, n) -> new Row(rs.getString(1), rs.getLong(2)))
                .optional();
    }

    private static AccountHolder person(AccountStatus status, long revision) {
        return new AccountHolder(UUID.randomUUID(), "CUST-000123", Ibans.fresh(), HolderType.PERSON, "Anna Schmidt",
                LocalDate.parse("1985-04-12"), "DE", null, status, OPENED_AT, revision);
    }

    private static AccountHolder revision(AccountHolder holder, AccountStatus status, long revision) {
        return new AccountHolder(holder.accountId(), holder.customerId(), holder.iban(), holder.holderType(),
                holder.holderName(), holder.dateOfBirth(), holder.country(), holder.organisationId(), status,
                holder.openedAt(), revision);
    }
}
