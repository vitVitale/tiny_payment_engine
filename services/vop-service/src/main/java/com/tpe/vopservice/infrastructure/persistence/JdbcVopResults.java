package com.tpe.vopservice.infrastructure.persistence;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.Iban;
import com.tpe.vopservice.application.references.VopResults;
import com.tpe.vopservice.domain.matching.VerificationResult;
import com.tpe.vopservice.domain.references.IssuedReference;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code vop_results} in PostgreSQL (specs/13-vop-service.md §5). */
@Repository
class JdbcVopResults implements VopResults {

    private final JdbcClient jdbc;

    JdbcVopResults(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void save(IssuedReference reference) {
        jdbc.sql("""
                        INSERT INTO vop_results (vop_reference, debtor_iban, payee_iban, payee_name, result,
                                                 actual_name, responded_by, created_at, expires_at)
                        VALUES (:reference, :debtorIban, :payeeIban, :payeeName, :result, :actualName,
                                :respondedBy, :createdAt, :expiresAt)""")
                .param("reference", reference.vopReference())
                .param("debtorIban", reference.debtorIban().value())
                .param("payeeIban", reference.payeeIban().value())
                .param("payeeName", reference.payeeName())
                .param("result", reference.result().name())
                .param("actualName", reference.actualName())
                .param("respondedBy", reference.respondedBy().value())
                .param("createdAt", Timestamp.from(reference.createdAt()))
                .param("expiresAt", Timestamp.from(reference.expiresAt()))
                .update();
    }

    @Override
    public Optional<IssuedReference> find(String vopReference) {
        return jdbc.sql("""
                        SELECT vop_reference, debtor_iban, payee_iban, payee_name, result, actual_name, responded_by,
                               created_at, expires_at
                        FROM vop_results WHERE vop_reference = ?""")
                .param(vopReference)
                .query((rs, n) -> new IssuedReference(
                        rs.getString("vop_reference"),
                        new Iban(rs.getString("debtor_iban")),
                        new Iban(rs.getString("payee_iban")),
                        rs.getString("payee_name"),
                        VerificationResult.valueOf(rs.getString("result")),
                        rs.getString("actual_name"),
                        new Bic(rs.getString("responded_by")),
                        rs.getTimestamp("created_at").toInstant(),
                        rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    @Override
    public boolean deleteIfCreatedBefore(String vopReference, Instant createdBefore) {
        return jdbc.sql("DELETE FROM vop_results WHERE vop_reference = ? AND created_at < ?")
                .param(vopReference)
                .param(Timestamp.from(createdBefore))
                .update() == 1;
    }

    @Override
    public void clearNames(String vopReference) {
        jdbc.sql("UPDATE vop_results SET payee_name = NULL, actual_name = NULL WHERE vop_reference = ?")
                .param(vopReference)
                .update();
    }

    @Override
    public long countNamedExpiredBefore(Instant expiredBefore) {
        return jdbc.sql("""
                        SELECT count(*) FROM vop_results
                        WHERE expires_at < ? AND (payee_name IS NOT NULL OR actual_name IS NOT NULL)""")
                .param(Timestamp.from(expiredBefore))
                .query(Long.class)
                .single();
    }
}
