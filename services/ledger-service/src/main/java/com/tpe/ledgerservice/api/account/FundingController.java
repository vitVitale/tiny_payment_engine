package com.tpe.ledgerservice.api.account;

import com.tpe.ledgerservice.api.IdempotentExecutor;
import com.tpe.ledgerservice.application.posting.FundingService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Test funding (specs/12-ledger-service.md §3.2, UC-10 3). The bean exists only with
 * {@code tpe.ledger.funding.enabled} ({@code local}, {@code test}); without it the path is {@code 404} (UC-10 3a,
 * LED-AC-15).
 */
@RestController
@ConditionalOnProperty(name = FundingController.ENABLED, havingValue = "true")
class FundingController {

    static final String ENABLED = "tpe.ledger.funding.enabled";

    private final FundingService funding;
    private final IdempotentExecutor idempotent;

    FundingController(FundingService funding, IdempotentExecutor idempotent) {
        this.funding = funding;
        this.idempotent = idempotent;
    }

    /** Idempotent by {@code Idempotency-Key} (12 §6.1); the JWT {@code sub} is the audit actor (LED-AC-18). */
    @PostMapping(path = "/v1/accounts/{accountId}/funding", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('BACKOFFICE_OPERATOR')")
    @ApiResponse(responseCode = "200", description = "Funded",
            content = @Content(schema = @Schema(implementation = FundingResponse.class)))
    ResponseEntity<String> fund(
            @PathVariable UUID accountId,
            @Parameter(required = true, description = "At most 200 characters; a retry replays the stored response")
            @RequestHeader(name = IdempotentExecutor.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody FundingRequest request, @AuthenticationPrincipal Jwt operator, HttpServletRequest http) {
        return idempotent.execute(idempotencyKey, http, request, HttpStatus.OK, () -> {
            request.validate();
            return FundingResponse.of(
                    funding.fund(accountId, request.amountMinor(), request.currency(), operator.getSubject()));
        });
    }
}
