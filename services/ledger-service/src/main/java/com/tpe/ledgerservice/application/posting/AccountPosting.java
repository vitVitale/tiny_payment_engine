package com.tpe.ledgerservice.application.posting;

import com.tpe.ledgerservice.domain.posting.EntryType;
import com.tpe.ledgerservice.domain.posting.Side;
import java.time.Instant;
import java.util.UUID;

/** A posting to one account with what its journal entry booked, a line of the entries feed (12 §3.2). */
public record AccountPosting(
        long postingId,
        UUID entryId,
        EntryType entryType,
        Side side,
        long amountMinor,
        UUID paymentId,
        String txId,
        Instant createdAt) {}
