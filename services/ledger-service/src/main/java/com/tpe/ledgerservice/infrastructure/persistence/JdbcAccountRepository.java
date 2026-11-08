package com.tpe.ledgerservice.infrastructure.persistence;

import com.tpe.ledgerservice.application.account.AccountRepository;
import com.tpe.ledgerservice.domain.account.Account;
import com.tpe.ledgerservice.domain.account.AccountKind;
import com.tpe.ledgerservice.domain.account.AccountStatus;
import com.tpe.ledgerservice.domain.account.BlockReason;
import com.tpe.ledgerservice.domain.account.CustomerId;
import com.tpe.ledgerservice.domain.account.Holder;
import com.tpe.ledgerservice.domain.account.HolderType;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.PartyName;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAccountRepository implements AccountRepository {

    private static final String SELECT = """
            SELECT a.account_id, iban, kind, customer_id, holder_type, holder_name, holder_dob, holder_country,
                   holder_org_id, status, currency, booked_minor, held_minor, version, revision, opened_at, closed_at,
                   ARRAY(SELECT b.reason FROM account_blocks b
                          WHERE b.account_id = a.account_id AND b.lifted_at IS NULL) AS active_blocks,
                   (SELECT coalesce(sum(p.amount_minor), 0) FROM pending_credits p
                     WHERE p.account_id = a.account_id AND p.status = 'ACTIVE') AS pending_credits_minor
              FROM accounts a""";

    private final JdbcClient jdbc;

    JdbcAccountRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long nextAccountNumber() {
        return jdbc.sql("SELECT nextval('account_number_seq')").query(Long.class).single();
    }

    @Override
    public void insert(Account account) {
        Holder holder = account.holder();
        jdbc.sql("""
                INSERT INTO accounts (account_id, iban, kind, customer_id, holder_type, holder_name, holder_dob,
                                      holder_country, holder_org_id, status, currency, booked_minor, held_minor, version,
                                      revision, opened_at, closed_at)
                VALUES (:accountId, :iban, :kind, :customerId, :holderType, :holderName, :holderDob,
                        :holderCountry, :holderOrgId, :status, :currency, :bookedMinor, :heldMinor, :version,
                        :revision, :openedAt, :closedAt)""")
                .param("accountId", account.accountId())
                .param("iban", account.iban().value())
                .param("kind", account.kind().name())
                .param("customerId", account.customerId() == null ? null : account.customerId().value())
                .param("holderType", holder == null ? null : holder.type().name())
                .param("holderName", holder == null ? null : holder.name().value())
                .param("holderDob", holder == null ? null : holder.dateOfBirth())
                .param("holderCountry", holder == null ? null : holder.countryOfResidence())
                .param("holderOrgId", holder == null ? null : holder.organisationId())
                .param("status", account.status().name())
                .param("currency", account.currency())
                .param("bookedMinor", account.bookedMinor())
                .param("heldMinor", account.heldMinor())
                .param("version", account.version())
                .param("revision", account.revision())
                .param("openedAt", utc(account.openedAt()))
                .param("closedAt", utc(account.closedAt()))
                .update();
    }

    @Override
    public Optional<Account> findById(UUID accountId) {
        return jdbc.sql(SELECT + " WHERE account_id = ?").param(accountId).query(JdbcAccountRepository::account)
                .optional();
    }

    /** {@code ORDER BY} under {@code FOR UPDATE}: PostgreSQL locks the rows as the sorted scan returns them. */
    @Override
    public List<Account> lockForUpdate(Collection<UUID> accountIds) {
        return jdbc.sql(SELECT + " WHERE a.account_id IN (:ids) ORDER BY a.account_id FOR UPDATE OF a")
                .param("ids", accountIds)
                .query(JdbcAccountRepository::account)
                .list();
    }

    @Override
    public boolean updateBalances(Account account) {
        return jdbc.sql("""
                UPDATE accounts SET booked_minor = :booked, held_minor = :held, version = version + 1
                 WHERE account_id = :accountId AND version = :version AND kind = 'CUSTOMER'""")
                .param("booked", account.bookedMinor())
                .param("held", account.heldMinor())
                .param("accountId", account.accountId())
                .param("version", account.version())
                .update() == 1;
    }

    @Override
    public boolean updateLifecycle(Account account) {
        return jdbc.sql("""
                UPDATE accounts SET status = :status, revision = :revision, closed_at = :closedAt,
                                    version = version + 1
                 WHERE account_id = :accountId AND version = :version AND kind = 'CUSTOMER'""")
                .param("status", account.status().name())
                .param("revision", account.revision())
                .param("closedAt", utc(account.closedAt()))
                .param("accountId", account.accountId())
                .param("version", account.version())
                .update() == 1;
    }

    @Override
    public boolean markEventsTombstoned(UUID accountId, Instant at) {
        return jdbc.sql("""
                UPDATE accounts SET events_tombstoned_at = ?
                 WHERE account_id = ? AND status = 'CLOSED' AND events_tombstoned_at IS NULL""")
                .params(utc(at), accountId)
                .update() == 1;
    }

    @Override
    public void addBlock(UUID accountId, BlockReason reason, Instant at, String actor, String comment) {
        jdbc.sql("""
                INSERT INTO account_blocks (account_id, reason, created_at, created_by, comment)
                VALUES (?, ?, ?, ?, ?)""")
                .params(accountId, reason.name(), utc(at), actor, comment)
                .update();
    }

    @Override
    public void liftBlock(UUID accountId, BlockReason reason, Instant at, String actor) {
        jdbc.sql("""
                UPDATE account_blocks SET lifted_at = ?, lifted_by = ?
                 WHERE account_id = ? AND reason = ? AND lifted_at IS NULL""")
                .params(utc(at), actor, accountId, reason.name())
                .update();
    }

    @Override
    public Optional<Account> findByIban(Iban iban) {
        return jdbc.sql(SELECT + " WHERE iban = ?").param(iban.value()).query(JdbcAccountRepository::account)
                .optional();
    }

    @Override
    public Optional<Account> findByIbanForShare(Iban iban) {
        return jdbc.sql(SELECT + " WHERE iban = ? FOR SHARE OF a").param(iban.value())
                .query(JdbcAccountRepository::account).optional();
    }

    @Override
    public List<Account> findByCustomerId(CustomerId customerId) {
        return jdbc.sql(SELECT + " WHERE customer_id = ? AND kind = 'CUSTOMER' ORDER BY opened_at, a.account_id")
                .param(customerId.value())
                .query(JdbcAccountRepository::account)
                .list();
    }

    private static Account account(ResultSet rs, int row) throws SQLException {
        String customerId = rs.getString("customer_id");
        String holderType = rs.getString("holder_type");
        Holder holder = holderType == null ? null : new Holder(
                HolderType.valueOf(holderType),
                new PartyName(rs.getString("holder_name")),
                rs.getObject("holder_dob", LocalDate.class),
                rs.getString("holder_country"),
                rs.getString("holder_org_id"));
        return new Account(
                rs.getObject("account_id", UUID.class),
                new Iban(rs.getString("iban")),
                AccountKind.valueOf(rs.getString("kind")),
                customerId == null ? null : new CustomerId(customerId),
                holder,
                AccountStatus.valueOf(rs.getString("status")),
                Arrays.stream((String[]) rs.getArray("active_blocks").getArray()).map(BlockReason::valueOf).toList(),
                rs.getString("currency"),
                rs.getLong("booked_minor"),
                rs.getLong("held_minor"),
                rs.getLong("pending_credits_minor"),
                rs.getLong("version"),
                rs.getLong("revision"),
                instant(rs, "opened_at"),
                instant(rs, "closed_at"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
