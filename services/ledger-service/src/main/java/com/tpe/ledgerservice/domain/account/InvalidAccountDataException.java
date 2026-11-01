package com.tpe.ledgerservice.domain.account;

import java.util.List;

/** Account data that breaks a rule of specs/12-ledger-service.md §3.2; the message never holds the input. */
public final class InvalidAccountDataException extends IllegalArgumentException {

    private final List<AccountDataViolation> violations;

    public InvalidAccountDataException(List<AccountDataViolation> violations) {
        super("invalid account data: " + violations);
        this.violations = List.copyOf(violations);
    }

    public List<AccountDataViolation> violations() {
        return violations;
    }
}
