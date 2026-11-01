package com.tpe.ledgerservice.api.account;

import com.tpe.ledgerservice.api.FieldError;
import com.tpe.ledgerservice.api.ValidationFailedException;
import com.tpe.ledgerservice.application.account.AccountService;
import com.tpe.ledgerservice.domain.account.Account;
import com.tpe.paymentdomain.DomainValidationException;
import com.tpe.paymentdomain.Iban;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The Account API of specs/12-ledger-service.md §3.2 (UC-10), for back-office operators. */
@RestController
@RequestMapping(path = "/v1/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
class AccountController {

    private final AccountService accounts;

    AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    /** The JWT {@code sub} is the audit actor (LED-AC-18). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('BACKOFFICE_OPERATOR')")
    ResponseEntity<AccountResponse> open(@RequestBody OpenAccountRequest request,
            @AuthenticationPrincipal Jwt operator) {
        Account account = accounts.open(request.toCommand(), operator.getSubject());
        return ResponseEntity.created(URI.create("/v1/accounts/" + account.accountId()))
                .body(AccountResponse.of(account));
    }

    @GetMapping("/{accountId}")
    @PreAuthorize("hasRole('BACKOFFICE_OPERATOR')")
    AccountResponse get(@PathVariable UUID accountId) {
        return AccountResponse.of(accounts.get(accountId));
    }

    @GetMapping(params = "iban")
    @PreAuthorize("hasRole('BACKOFFICE_OPERATOR')")
    AccountResponse getByIban(@RequestParam String iban) {
        Iban parsed;
        try {
            parsed = new Iban(iban);
        } catch (DomainValidationException e) {
            throw new ValidationFailedException(List.of(new FieldError("iban", e.code().name())));
        }
        return AccountResponse.of(accounts.getByIban(parsed));
    }
}
