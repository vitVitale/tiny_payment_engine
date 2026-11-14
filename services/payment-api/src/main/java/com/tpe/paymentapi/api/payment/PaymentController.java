package com.tpe.paymentapi.api.payment;

import com.tpe.contracts.payment.PaymentAccepted;
import com.tpe.paymentapi.api.security.Caller;
import com.tpe.paymentapi.application.idempotency.IdempotencyGuard;
import com.tpe.paymentapi.application.idempotency.IdempotentRequest;
import com.tpe.paymentapi.application.submission.PaymentSubmission;
import com.tpe.paymentapi.application.validation.PaymentInstruction;
import com.tpe.paymentapi.application.validation.PaymentValidator;
import com.tpe.paymentapi.application.validation.ValidatedPayment;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.json.JsonMapper;

/**
 * The payment controller (specs/10-payment-api.md §3.1, UC-01): a channel initiates a payment. The processing order
 * of §3.1: the token (security filter chain), debtor ownership, the {@code Idempotency-Key} and its guard (§5.1),
 * then for a new key validation, the VoP reference check and the submit. The request body is never logged.
 */
@RestController
class PaymentController {

    static final String PAYMENTS = "/v1/payments";

    private final PaymentValidator validator;
    private final PaymentSubmission submission;
    private final IdempotencyGuard guard;
    private final RequestHash hash;

    PaymentController(PaymentValidator validator, PaymentSubmission submission, IdempotencyGuard guard,
            JsonMapper json) {
        this.validator = validator;
        this.submission = submission;
        this.guard = guard;
        this.hash = new RequestHash(json);
    }

    @Operation(summary = "Initiate an SCT Inst payment (UC-01)", security = @SecurityRequirement(name = "bearer",
            scopes = "payments:write"))
    @ApiResponse(responseCode = "202", description = "Accepted; Location is the payment's status. The same key and "
            + "body again answer the same payment with its current status")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED with errors[], IDEMPOTENCY_KEY_MISSING, "
            + "IDEMPOTENCY_KEY_INVALID", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "401", description = "No valid Bearer token",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "403", description = "Scope payments:write missing, or DEBTOR_ACCOUNT_NOT_OWNED",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "IDEMPOTENCY_KEY_IN_PROGRESS: a request with the key is being "
            + "processed", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "422", description = "VOP_REQUIRED, CREDITOR_AGENT_NOT_REACHABLE, "
            + "IDEMPOTENCY_KEY_REUSED", content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "ORCHESTRATOR_UNAVAILABLE, VOP_UNAVAILABLE; retry after "
            + "Retry-After with the same Idempotency-Key", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(path = PAYMENTS, consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PaymentResponse> initiate(
            @Parameter(in = ParameterIn.HEADER, required = true,
                    description = "The same key is the same payment",
                    schema = @Schema(type = "string", pattern = "^[A-Za-z0-9_-]{1,64}$", minLength = 1,
                            maxLength = 64))
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
            @RequestBody PaymentInstruction instruction,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        Caller caller = Caller.of(token);
        caller.requireOwns(instruction.debtor() == null ? null : instruction.debtor().iban());
        IdempotencyKeys.require(idempotencyKey);
        var request = new IdempotentRequest(caller.clientId(), idempotencyKey, hash.of("POST", PAYMENTS, instruction));
        PaymentAccepted accepted = guard.initiate(request, () -> {
            ValidatedPayment payment = validator.validate(instruction);
            return submission.submit(caller.clientId(), idempotencyKey, payment);
        });
        return ResponseEntity.accepted()
                .location(URI.create(PAYMENTS + "/" + accepted.paymentId().value()))
                .body(PaymentResponse.of(accepted));
    }
}
