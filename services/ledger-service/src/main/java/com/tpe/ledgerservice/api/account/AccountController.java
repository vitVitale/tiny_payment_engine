package com.tpe.ledgerservice.api.account;

import com.tpe.ledgerservice.api.Callers;
import com.tpe.ledgerservice.api.FieldError;
import com.tpe.ledgerservice.api.IdempotentExecutor;
import com.tpe.ledgerservice.api.ValidationFailedException;
import com.tpe.ledgerservice.application.account.AccountService;
import com.tpe.paymentdomain.DomainValidationException;
import com.tpe.paymentdomain.Iban;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The Account API of specs/12-ledger-service.md §3.2 (UC-10), for back-office operators. */
@RestController
@RequestMapping(path = "/v1/accounts", produces = MediaType.APPLICATION_JSON_VALUE)
class AccountController {

    private static final String MAY_CHANGE_BLOCKS =
            "hasAnyRole('BACKOFFICE_OPERATOR', 'COMPLIANCE_OFFICER') or hasAuthority('SCOPE_ledger:sanctions-block')";

    private final AccountService accounts;
    private final IdempotentExecutor idempotent;

    AccountController(AccountService accounts, IdempotentExecutor idempotent) {
        this.accounts = accounts;
        this.idempotent = idempotent;
    }

    /**
     * Idempotent by {@code Idempotency-Key} (12 §6.1): a retry replays the stored {@code 201} or {@code 422}. The JWT
     * {@code sub} is the audit actor (LED-AC-18).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('BACKOFFICE_OPERATOR')")
    @ApiResponse(responseCode = "201", description = "Created",
            content = @Content(schema = @Schema(implementation = AccountResponse.class)))
    ResponseEntity<String> open(
            // required: absent, it is our 400 IDEMPOTENCY_KEY_MISSING rather than Spring's
            @Parameter(required = true, description = "At most 200 characters; a retry replays the stored response")
            @RequestHeader(name = IdempotentExecutor.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody OpenAccountRequest request, @AuthenticationPrincipal Jwt operator, HttpServletRequest http) {
        return idempotent.execute(idempotencyKey, http, request, HttpStatus.CREATED,
                () -> AccountResponse.of(accounts.open(request.toCommand(), operator.getSubject())));
    }

    /**
     * Adds a block (12 §3.2, UC-10). {@code OPERATOR} blocks are for back-office operators; {@code SANCTIONS*} ones
     * for a compliance officer or risk-service ({@code ledger:sanctions-block}), anyone else gets
     * {@code 403 SANCTIONS_BLOCK_PROTECTED}. A reason already active is a no-op {@code 200}.
     */
    @PostMapping("/{accountId}/block")
    @PreAuthorize(MAY_CHANGE_BLOCKS)
    @ApiResponse(responseCode = "200", description = "Blocked",
            content = @Content(schema = @Schema(implementation = AccountResponse.class)))
    ResponseEntity<String> block(
            @PathVariable UUID accountId,
            @Parameter(required = true, description = "At most 200 characters; a retry replays the stored response")
            @RequestHeader(name = IdempotentExecutor.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody BlockRequest request, Authentication caller, HttpServletRequest http) {
        return idempotent.execute(idempotencyKey, http, request, HttpStatus.OK, () -> AccountResponse.of(
                accounts.block(accountId, request.validReason(), request.comment(), Callers.of(caller)).account()));
    }

    /**
     * Lifts the block of a reason (12 §3.2, UC-10), with the same rules per reason as {@link #block}; the account
     * stays {@code BLOCKED} while other blocks remain. A reason that is not active is a no-op {@code 200}.
     */
    @PostMapping("/{accountId}/unblock")
    @PreAuthorize(MAY_CHANGE_BLOCKS)
    @ApiResponse(responseCode = "200", description = "Unblocked",
            content = @Content(schema = @Schema(implementation = AccountResponse.class)))
    ResponseEntity<String> unblock(
            @PathVariable UUID accountId,
            @Parameter(required = true, description = "At most 200 characters; a retry replays the stored response")
            @RequestHeader(name = IdempotentExecutor.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody BlockRequest request, Authentication caller, HttpServletRequest http) {
        return idempotent.execute(idempotencyKey, http, request, HttpStatus.OK, () -> AccountResponse.of(
                accounts.unblock(accountId, request.validReason(), request.comment(), Callers.of(caller)).account()));
    }

    /**
     * Closes an empty account (12 §3.2, UC-10): booked balance 0, no {@code ACTIVE} holds or pending credits, else
     * {@code 422 ACCOUNT_NOT_EMPTY}; an active sanctions block refuses it with {@code 422 ACCOUNT_BLOCKED}. Publishes
     * {@code AccountClosed}; 13 months later the account's key is deleted from {@code accounts.events}.
     */
    @PostMapping("/{accountId}/close")
    @PreAuthorize("hasRole('BACKOFFICE_OPERATOR')")
    @ApiResponse(responseCode = "200", description = "Closed",
            content = @Content(schema = @Schema(implementation = AccountResponse.class)))
    ResponseEntity<String> close(
            @PathVariable UUID accountId,
            @Parameter(required = true, description = "At most 200 characters; a retry replays the stored response")
            @RequestHeader(name = IdempotentExecutor.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> body, @AuthenticationPrincipal Jwt operator,
            HttpServletRequest http) {
        return idempotent.execute(idempotencyKey, http, body == null ? Map.of() : body, HttpStatus.OK,
                () -> AccountResponse.of(accounts.close(accountId, operator.getSubject())));
    }

    @GetMapping("/{accountId}")
    @PreAuthorize("hasRole('BACKOFFICE_OPERATOR')")
    AccountResponse get(@PathVariable UUID accountId) {
        return AccountResponse.of(accounts.read(accountId));
    }

    /**
     * The account's postings, newest first, 100 per page (12 §3.2). {@code from} is inclusive, {@code to} exclusive;
     * {@code cursor} is the {@code nextCursor} of the previous page.
     */
    @GetMapping("/{accountId}/entries")
    @PreAuthorize("hasRole('BACKOFFICE_OPERATOR')")
    EntriesResponse entries(@PathVariable UUID accountId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(required = false) String cursor) {
        return EntriesResponse.of(accounts.entries(accountId, from, to, EntriesCursor.decode(cursor)));
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
        return AccountResponse.of(accounts.readByIban(parsed));
    }
}
