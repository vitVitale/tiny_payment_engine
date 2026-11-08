package com.tpe.ledgerservice.api.posting;

import com.tpe.ledgerservice.api.IdempotentExecutor;
import com.tpe.ledgerservice.api.IdempotentExecutor.Outcome;
import com.tpe.ledgerservice.api.InternalApi;
import com.tpe.ledgerservice.application.reservation.PendingCreditCreation;
import com.tpe.ledgerservice.application.reservation.PendingCreditService;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pending credits of the Posting API (specs/12-ledger-service.md §3.1), for payment-orchestrator. Mutating calls go
 * through the idempotency guard; the orchestrator's keys are {@code pending-credit-create:<paymentId>},
 * {@code pending-credit-confirm:<paymentId>} and {@code pending-credit-cancel:<paymentId>}.
 */
@InternalApi
@RestController
@RequestMapping(path = "/v1/pending-credits", produces = MediaType.APPLICATION_JSON_VALUE)
// no token until M5 (00 §12.1)
@SecurityRequirements
class PendingCreditController {

    private final PendingCreditService pendingCredits;
    private final IdempotentExecutor idempotent;

    PendingCreditController(PendingCreditService pendingCredits, IdempotentExecutor idempotent) {
        this.pendingCredits = pendingCredits;
        this.idempotent = idempotent;
    }

    /** {@code 201} with a new pending credit; {@code 200} with the existing {@code ACTIVE} one of a retry. */
    @PostMapping
    @ApiResponse(responseCode = "201", description = "Created",
            content = @Content(schema = @Schema(implementation = PendingCreditResponse.class)))
    @ApiResponse(responseCode = "200", description = "The existing pending credit of a retry",
            content = @Content(schema = @Schema(implementation = PendingCreditResponse.class)))
    ResponseEntity<String> create(
            @Parameter(required = true,
                    description = "pending-credit-create:<paymentId>; a retry replays the stored response")
            @RequestHeader(name = IdempotentExecutor.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody CreatePendingCreditRequest request, HttpServletRequest http) {
        return idempotent.executeWithStatus(idempotencyKey, http, request, () -> {
            PendingCreditCreation creation = pendingCredits.create(request.toCommand());
            return new Outcome(creation.created() ? HttpStatus.CREATED : HttpStatus.OK,
                    PendingCreditResponse.of(creation.pendingCredit()));
        });
    }

    /**
     * Dr CSM settlement account / Cr payee, whatever the payee's status; already confirmed → {@code 200} with the same
     * result.
     */
    @PostMapping("/{pendingCreditId}/confirm")
    @ApiResponse(responseCode = "200", description = "Confirmed",
            content = @Content(schema = @Schema(implementation = ConfirmationResponse.class)))
    ResponseEntity<String> confirm(
            @PathVariable UUID pendingCreditId,
            @Parameter(required = true,
                    description = "pending-credit-confirm:<paymentId>; a retry replays the stored response")
            @RequestHeader(name = IdempotentExecutor.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> body, HttpServletRequest http) {
        return idempotent.execute(idempotencyKey, http, body == null ? Map.of() : body, HttpStatus.OK,
                () -> ConfirmationResponse.of(pendingCredits.confirm(pendingCreditId)));
    }

    /** {@code ACTIVE} → {@code CANCELLED}; already cancelled → {@code 200}; unknown id → a tombstone (ADR-009). */
    @PostMapping("/{pendingCreditId}/cancel")
    @ApiResponse(responseCode = "200", description = "Cancelled",
            content = @Content(schema = @Schema(implementation = PendingCreditResponse.class)))
    ResponseEntity<String> cancel(
            @PathVariable UUID pendingCreditId,
            @Parameter(required = true,
                    description = "pending-credit-cancel:<paymentId>; a retry replays the stored response")
            @RequestHeader(name = IdempotentExecutor.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> body, HttpServletRequest http) {
        return idempotent.execute(idempotencyKey, http, body == null ? Map.of() : body, HttpStatus.OK,
                () -> PendingCreditResponse.of(pendingCredits.cancel(pendingCreditId)));
    }

    @GetMapping("/{pendingCreditId}")
    PendingCreditResponse get(@PathVariable UUID pendingCreditId) {
        return PendingCreditResponse.of(pendingCredits.get(pendingCreditId));
    }
}
