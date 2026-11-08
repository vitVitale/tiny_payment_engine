package com.tpe.ledgerservice.domain.reservation;

import java.util.UUID;

/**
 * What a pending credit is for (ADR-010): the incoming payment, and the transaction's business identity
 * ({@code debtorAgentBic}, {@code txId}), kept as reference columns. Each part may be {@code null}.
 */
public record IncomingReference(UUID paymentId, String txId, String debtorAgentBic) {}
