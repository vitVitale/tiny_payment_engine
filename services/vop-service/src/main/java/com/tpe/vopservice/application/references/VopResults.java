package com.tpe.vopservice.application.references;

import com.tpe.vopservice.domain.references.IssuedReference;
import java.time.Instant;
import java.util.Optional;

/** {@code vop_results} (specs/13-vop-service.md §5): the issued references and their retention (§6.3). */
public interface VopResults {

    void save(IssuedReference reference);

    /** Empty for an unknown reference, or one retention has deleted. */
    Optional<IssuedReference> find(String vopReference);

    /** Deletes the row if it was created before {@code createdBefore}; {@code true} if it did. */
    boolean deleteIfCreatedBefore(String vopReference, Instant createdBefore);

    /** Sets payee and actual name to {@code NULL}. */
    void clearNames(String vopReference);

    /** Rows that still hold a name and expired before {@code expiredBefore}: the retention job's backlog. */
    long countNamedExpiredBefore(Instant expiredBefore);
}
