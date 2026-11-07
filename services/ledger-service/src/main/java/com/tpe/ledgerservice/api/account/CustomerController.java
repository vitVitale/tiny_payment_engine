package com.tpe.ledgerservice.api.account;

import com.tpe.ledgerservice.api.FieldError;
import com.tpe.ledgerservice.api.ValidationFailedException;
import com.tpe.ledgerservice.application.account.AccountService;
import com.tpe.ledgerservice.domain.account.CustomerId;
import com.tpe.ledgerservice.domain.account.InvalidAccountDataException;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The accounts of a customer, for risk-service to block all of them (specs/12-ledger-service.md §3.2): its
 * client-credentials JWT needs scope {@code ledger:customer-accounts} (ADR-012).
 */
@RestController
class CustomerController {

    private final AccountService accounts;

    CustomerController(AccountService accounts) {
        this.accounts = accounts;
    }

    @GetMapping(path = "/v1/customers/{customerId}/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAuthority('SCOPE_ledger:customer-accounts')")
    CustomerAccountsResponse accounts(@PathVariable String customerId) {
        CustomerId customer;
        try {
            customer = new CustomerId(customerId);
        } catch (InvalidAccountDataException e) {
            throw new ValidationFailedException(e.violations().stream()
                    .map(violation -> new FieldError(violation.field(), violation.code()))
                    .toList());
        }
        return CustomerAccountsResponse.of(accounts.byCustomer(customer));
    }
}
