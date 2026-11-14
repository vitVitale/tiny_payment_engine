package com.tpe.paymentapi.api.vop;

import com.tpe.contracts.vop.VerificationResponse;
import com.tpe.paymentapi.api.security.Caller;
import com.tpe.paymentapi.application.verification.PayeeVerification;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payee verification controller (specs/10-payment-api.md §3.4, UC-02): the payer verifies a payee before paying.
 * The debtor account must be one of the token's ({@code 403 DEBTOR_ACCOUNT_NOT_OWNED}); vop-service's answer is
 * returned unchanged. Not idempotent by key: every call issues a new reference. The body is never logged.
 */
@RestController
class PayeeVerificationController {

    static final String PAYEE_VERIFICATIONS = "/v1/payee-verifications";

    private final PayeeVerification verification;

    PayeeVerificationController(PayeeVerification verification) {
        this.verification = verification;
    }

    @Operation(summary = "Verify a payee before paying (UC-02)", security = @SecurityRequirement(name = "bearer",
            scopes = "vop:verify"))
    @ApiResponse(responseCode = "201", description = "The verification and the reference to pay with")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED with errors[], e.g. INVALID_IBAN",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Scope vop:verify missing, or DEBTOR_ACCOUNT_NOT_OWNED",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "PAYEE_BANK_UNKNOWN",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "VOP_UNAVAILABLE; retry after Retry-After",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(path = PAYEE_VERIFICATIONS, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    VerificationResponse verify(@RequestBody PayeeVerification.Request request,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        Caller.of(token).requireOwns(request.debtorIban());
        return verification.verify(request);
    }
}
