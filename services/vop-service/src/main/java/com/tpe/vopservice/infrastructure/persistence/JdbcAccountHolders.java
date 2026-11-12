package com.tpe.vopservice.infrastructure.persistence;

import com.tpe.paymentdomain.Iban;
import com.tpe.vopservice.application.accounts.AccountHolders;
import com.tpe.vopservice.domain.accounts.AccountHolder;
import com.tpe.vopservice.domain.accounts.AccountHolder.AccountStatus;
import com.tpe.vopservice.domain.accounts.AccountHolder.HolderType;
import com.tpe.vopservice.domain.accounts.ProjectedHolder;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code account_holders} in PostgreSQL. The revision check is part of the upsert, so two deliveries racing on one
 * account cannot let the older snapshot win.
 */
@Repository
class JdbcAccountHolders implements AccountHolders {

    private final JdbcClient jdbc;

    JdbcAccountHolders(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void upsertIfNewer(AccountHolder holder, String normalisedName, Instant updatedAt) {
        jdbc.sql("""
                        INSERT INTO account_holders (account_id, iban, holder_type, holder_name, normalised_name,
                                                     organisation_id, status, revision, updated_at)
                        VALUES (:accountId, :iban, :holderType, :holderName, :normalisedName,
                                :organisationId, :status, :revision, :updatedAt)
                        ON CONFLICT (account_id) DO UPDATE SET
                          iban = EXCLUDED.iban, holder_type = EXCLUDED.holder_type,
                          holder_name = EXCLUDED.holder_name, normalised_name = EXCLUDED.normalised_name,
                          organisation_id = EXCLUDED.organisation_id, status = EXCLUDED.status,
                          revision = EXCLUDED.revision, updated_at = EXCLUDED.updated_at
                        WHERE account_holders.revision < EXCLUDED.revision""")
                .param("accountId", holder.accountId())
                .param("iban", holder.iban())
                .param("holderType", holder.holderType().name())
                .param("holderName", holder.holderName())
                .param("normalisedName", normalisedName)
                .param("organisationId", holder.organisationId())
                .param("status", holder.status().name())
                .param("revision", holder.revision())
                .param("updatedAt", Timestamp.from(updatedAt))
                .update();
    }

    @Override
    public void delete(UUID accountId) {
        jdbc.sql("DELETE FROM account_holders WHERE account_id = ?").param(accountId).update();
    }

    @Override
    public Optional<ProjectedHolder> findByIban(Iban iban) {
        return jdbc.sql("""
                        SELECT account_id, iban, holder_type, holder_name, normalised_name, organisation_id, status,
                               revision
                        FROM account_holders WHERE iban = ?""")
                .param(iban.value())
                .query((rs, n) -> new ProjectedHolder(
                        new AccountHolder(
                                rs.getObject("account_id", UUID.class),
                                rs.getString("iban"),
                                HolderType.valueOf(rs.getString("holder_type")),
                                rs.getString("holder_name"),
                                rs.getString("organisation_id"),
                                AccountStatus.valueOf(rs.getString("status")),
                                rs.getLong("revision")),
                        rs.getString("normalised_name")))
                .optional();
    }
}
