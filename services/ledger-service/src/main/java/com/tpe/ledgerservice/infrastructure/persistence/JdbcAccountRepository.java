package com.tpe.ledgerservice.infrastructure.persistence;

import com.tpe.ledgerservice.application.account.AccountRepository;
import com.tpe.ledgerservice.domain.account.Account;
import com.tpe.ledgerservice.domain.account.AccountKind;
import com.tpe.ledgerservice.domain.account.AccountStatus;
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
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAccountRepository implements AccountRepository {

    private static final String SELECT = """
            SELECT account_id, iban, kind, customer_id, holder_type, holder_name, holder_dob, holder_country,
                   holder_org_id, status, currency, booked_minor, held_minor, version, revision, opened_at, closed_at
              FROM accounts""";

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

    @Override
    public Optional<Account> findByIban(Iban iban) {
        return jdbc.sql(SELECT + " WHERE iban = ?").param(iban.value()).query(JdbcAccountRepository::account)
                .optional();
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
                rs.getString("currency"),
                rs.getLong("booked_minor"),
                rs.getLong("held_minor"),
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
