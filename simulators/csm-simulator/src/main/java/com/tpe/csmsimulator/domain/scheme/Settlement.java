package com.tpe.csmsimulator.domain.scheme;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.TxId;
import java.time.Instant;
import java.util.Objects;

/**
 * One entry of the settlement log, the CSM's view of what moved (specs/17-csm-simulator.md §3.2), read by the ledger
 * reconciliation invariant INV-10.
 */
public record Settlement(TxId txId, Bic debtorAgentBic, Direction direction, long amountMinor, Instant settledAt) {

    /** Seen from our bank: {@code OUTGOING} when we are the originator. */
    public enum Direction { OUTGOING, INCOMING }

    public Settlement {
        Objects.requireNonNull(txId, "txId");
        Objects.requireNonNull(debtorAgentBic, "debtorAgentBic");
        Objects.requireNonNull(direction, "direction");
        Objects.requireNonNull(settledAt, "settledAt");
    }
}
