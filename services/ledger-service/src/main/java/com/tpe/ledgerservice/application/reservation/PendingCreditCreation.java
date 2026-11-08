package com.tpe.ledgerservice.application.reservation;

import com.tpe.ledgerservice.domain.reservation.PendingCredit;

/**
 * The answer to a create pending credit.
 *
 * @param created {@code true} for a new pending credit ({@code 201}), {@code false} for the existing one a retry finds
 *     ({@code 200})
 */
public record PendingCreditCreation(PendingCredit pendingCredit, boolean created) {}
