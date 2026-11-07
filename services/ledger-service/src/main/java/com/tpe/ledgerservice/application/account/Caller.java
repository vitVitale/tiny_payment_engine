package com.tpe.ledgerservice.application.account;

import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import com.tpe.ledgerservice.domain.account.BlockReason;
import java.util.Objects;

/**
 * Who changes an account's blocks (specs/12-ledger-service.md §3.2, ADR-012): {@code OPERATOR} blocks belong to
 * back-office operators; the {@code SANCTIONS*} reasons to compliance officers and risk-service.
 *
 * @param actor the audit actor: the staff member's JWT {@code sub}, or {@code risk-service}
 * @param backofficeOperator the token has role {@code BACKOFFICE_OPERATOR}
 * @param sanctionsAuthority the token has role {@code COMPLIANCE_OFFICER} or scope {@code ledger:sanctions-block}
 */
public record Caller(String actor, boolean backofficeOperator, boolean sanctionsAuthority) {

    /** The audit actor of risk-service's client-credentials token (§8, LED-AC-18). */
    public static final String RISK_SERVICE = "risk-service";

    public Caller {
        Objects.requireNonNull(actor, "actor");
    }

    /**
     * @throws LedgerException {@code SANCTIONS_BLOCK_PROTECTED} for a back-office operator and a {@code SANCTIONS*}
     *     reason; {@code FORBIDDEN} for any other reason the caller may not touch
     */
    void requireMayChange(BlockReason reason) {
        boolean allowed = reason.sanctions() ? sanctionsAuthority : backofficeOperator;
        if (allowed) {
            return;
        }
        if (reason.sanctions() && backofficeOperator) {
            throw new LedgerException(LedgerError.SANCTIONS_BLOCK_PROTECTED,
                    "only a compliance officer or risk-service may change a " + reason + " block");
        }
        throw new LedgerException(LedgerError.FORBIDDEN, "the caller may not change a " + reason + " block");
    }
}
