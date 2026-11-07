package com.tpe.ledgerservice.application.reservation;

import com.tpe.ledgerservice.domain.reservation.Hold;

/**
 * The answer to a create hold.
 *
 * @param created {@code true} for a new hold ({@code 201}), {@code false} for the existing one a retry finds
 *     ({@code 200})
 */
public record HoldCreation(Hold hold, boolean created) {}
