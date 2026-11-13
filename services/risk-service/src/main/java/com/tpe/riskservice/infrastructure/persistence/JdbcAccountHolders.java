package com.tpe.riskservice.infrastructure.persistence;

import com.tpe.riskservice.application.accounts.AccountHolders;
import com.tpe.riskservice.domain.accounts.AccountHolder;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
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
    public void upsertIfNewer(AccountHolder holder, Instant updatedAt) {
        jdbc.sql("""
                        INSERT INTO account_holders (account_id, customer_id, iban, holder_type, holder_name,
                                                     date_of_birth, country, organisation_id, status, opened_at,
                                                     revision, updated_at)
                        VALUES (:accountId, :customerId, :iban, :holderType, :holderName, :dateOfBirth, :country,
                                :organisationId, :status, :openedAt, :revision, :updatedAt)
                        ON CONFLICT (account_id) DO UPDATE SET
                          customer_id = EXCLUDED.customer_id, iban = EXCLUDED.iban,
                          holder_type = EXCLUDED.holder_type, holder_name = EXCLUDED.holder_name,
                          date_of_birth = EXCLUDED.date_of_birth, country = EXCLUDED.country,
                          organisation_id = EXCLUDED.organisation_id, status = EXCLUDED.status,
                          opened_at = EXCLUDED.opened_at, revision = EXCLUDED.revision,
                          updated_at = EXCLUDED.updated_at
                        WHERE account_holders.revision < EXCLUDED.revision""")
                .param("accountId", holder.accountId())
                .param("customerId", holder.customerId())
                .param("iban", holder.iban())
                .param("holderType", holder.holderType().name())
                .param("holderName", holder.holderName())
                .param("dateOfBirth", holder.dateOfBirth() == null ? null : Date.valueOf(holder.dateOfBirth()))
                .param("country", holder.country())
                .param("organisationId", holder.organisationId())
                .param("status", holder.status().name())
                .param("openedAt", Timestamp.from(holder.openedAt()))
                .param("revision", holder.revision())
                .param("updatedAt", Timestamp.from(updatedAt))
                .update();
    }

    @Override
    public void delete(UUID accountId) {
        jdbc.sql("DELETE FROM account_holders WHERE account_id = ?").param(accountId).update();
    }
}
