package com.tpe.ledgerservice.api.account;

import com.tpe.ledgerservice.api.FieldError;
import com.tpe.ledgerservice.api.ValidationFailedException;
import com.tpe.ledgerservice.application.account.OpenAccount;
import com.tpe.ledgerservice.domain.account.AccountDataViolation;
import com.tpe.ledgerservice.domain.account.CustomerId;
import com.tpe.ledgerservice.domain.account.Holder;
import com.tpe.ledgerservice.domain.account.HolderType;
import com.tpe.ledgerservice.domain.account.InvalidAccountDataException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** {@code POST /v1/accounts} (specs/12-ledger-service.md §3.2). */
record OpenAccountRequest(String customerId, HolderRequest holder) {

    record HolderRequest(
            HolderType type,
            String name,
            LocalDate dateOfBirth,
            String countryOfResidence,
            String organisationId) {}

    /**
     * Every invalid field is a {@code 400}; a missing or blank {@code customerId} passes as {@code null}, which the
     * account service refuses with {@code 422 CUSTOMER_ID_MISSING}.
     */
    OpenAccount toCommand() {
        List<AccountDataViolation> violations = new ArrayList<>();
        CustomerId customer = null;
        if (customerId != null && !customerId.isBlank()) {
            try {
                customer = new CustomerId(customerId);
            } catch (InvalidAccountDataException e) {
                violations.addAll(e.violations());
            }
        }
        Holder accountHolder = null;
        if (holder == null) {
            violations.add(new AccountDataViolation("holder", "REQUIRED"));
        } else {
            try {
                accountHolder = Holder.of(holder.type(), holder.name(), holder.dateOfBirth(),
                        holder.countryOfResidence(), holder.organisationId());
            } catch (InvalidAccountDataException e) {
                violations.addAll(e.violations());
            }
        }
        if (!violations.isEmpty()) {
            throw new ValidationFailedException(violations.stream()
                    .map(violation -> new FieldError(violation.field(), violation.code()))
                    .toList());
        }
        return new OpenAccount(customer, accountHolder);
    }
}
