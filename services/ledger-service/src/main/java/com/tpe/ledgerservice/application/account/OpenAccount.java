package com.tpe.ledgerservice.application.account;

import com.tpe.ledgerservice.domain.account.CustomerId;
import com.tpe.ledgerservice.domain.account.Holder;

/**
 * Open a customer account (UC-10 1).
 *
 * @param customerId {@code null} when the request had none: refused with {@code CUSTOMER_ID_MISSING}
 */
public record OpenAccount(CustomerId customerId, Holder holder) {}
