package com.tpe.ledgerservice.application.reservation;

import com.tpe.ledgerservice.domain.reservation.Hold;
import java.util.UUID;

/**
 * A settled hold (specs/12-ledger-service.md §3.1).
 *
 * @param creditorAccountId the payee of an on-us settlement, {@code null} for one to the CSM settlement account
 */
public record Settlement(Hold hold, UUID creditorAccountId) {}
