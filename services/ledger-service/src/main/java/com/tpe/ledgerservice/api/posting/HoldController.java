package com.tpe.ledgerservice.api.posting;

import com.tpe.ledgerservice.api.IdempotentExecutor;
import com.tpe.ledgerservice.api.IdempotentExecutor.Outcome;
import com.tpe.ledgerservice.api.InternalApi;
import com.tpe.ledgerservice.application.reservation.HoldCreation;
import com.tpe.ledgerservice.application.reservation.HoldService;
import com.tpe.ledgerservice.application.reservation.SettlementService;
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
 * Holds of the Posting API (specs/12-ledger-service.md §3.1), for payment-orchestrator. Mutating calls go through the
 * idempotency guard; the orchestrator's keys are {@code hold-create:<holdId>}, {@code hold-release:<holdId>},
 * {@code hold-settle:<holdId>} and {@code hold-settle-on-us:<holdId>}.
 */
@InternalApi
@RestController
@RequestMapping(path = "/v1/holds", produces = MediaType.APPLICATION_JSON_VALUE)
// no token until M5 (00 §12.1)
@SecurityRequirements
class HoldController {

    private final HoldService holds;
    private final SettlementService settlements;
    private final IdempotentExecutor idempotent;

    HoldController(HoldService holds, SettlementService settlements, IdempotentExecutor idempotent) {
        this.holds = holds;
        this.settlements = settlements;
        this.idempotent = idempotent;
    }

    /** {@code 201} with a new hold; {@code 200} with the existing {@code ACTIVE} hold of a retry. */
    @PostMapping
    @ApiResponse(responseCode = "201", description = "Created",
            content = @Content(schema = @Schema(implementation = HoldResponse.class)))
    @ApiResponse(responseCode = "200", description = "The existing hold of a retry",
            content = @Content(schema = @Schema(implementation = HoldResponse.class)))
    ResponseEntity<String> create(
            @Parameter(required = true, description = "hold-create:<holdId>; a retry replays the stored response")
            @RequestHeader(name = IdempotentExecutor.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody CreateHoldRequest request, HttpServletRequest http) {
        return idempotent.executeWithStatus(idempotencyKey, http, request, () -> {
            HoldCreation creation = holds.create(request.toCommand());
            return new Outcome(creation.created() ? HttpStatus.CREATED : HttpStatus.OK,
                    HoldResponse.of(creation.hold()));
        });
    }

    /** {@code ACTIVE} → {@code RELEASED}; already released → {@code 200}; unknown id → a tombstone (ADR-009). */
    @PostMapping("/{holdId}/release")
    @ApiResponse(responseCode = "200", description = "Released",
            content = @Content(schema = @Schema(implementation = HoldResponse.class)))
    ResponseEntity<String> release(
            @PathVariable UUID holdId,
            @Parameter(required = true, description = "hold-release:<holdId>; a retry replays the stored response")
            @RequestHeader(name = IdempotentExecutor.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> body, HttpServletRequest http) {
        return idempotent.execute(idempotencyKey, http, body == null ? Map.of() : body, HttpStatus.OK,
                () -> HoldResponse.of(holds.release(holdId)));
    }

    /**
     * Dr payer / Cr CSM settlement account, whatever the payer's status; already settled → {@code 200} with the same
     * result.
     */
    @PostMapping("/{holdId}/settle")
    @ApiResponse(responseCode = "200", description = "Settled",
            content = @Content(schema = @Schema(implementation = SettlementResponse.class)))
    ResponseEntity<String> settle(
            @PathVariable UUID holdId,
            @Parameter(required = true, description = "hold-settle:<holdId>; a retry replays the stored response")
            @RequestHeader(name = IdempotentExecutor.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody(required = false) Map<String, Object> body, HttpServletRequest http) {
        return idempotent.execute(idempotencyKey, http, body == null ? Map.of() : body, HttpStatus.OK,
                () -> SettlementResponse.of(settlements.settle(holdId)));
    }

    /** Dr payer / Cr payee after the payee check; a refusal ({@code 422}) leaves the hold {@code ACTIVE}. */
    @PostMapping("/{holdId}/settle-on-us")
    @ApiResponse(responseCode = "200", description = "Settled on-us",
            content = @Content(schema = @Schema(implementation = SettlementResponse.class)))
    ResponseEntity<String> settleOnUs(
            @PathVariable UUID holdId,
            @Parameter(required = true, description = "hold-settle-on-us:<holdId>; a retry replays the stored response")
            @RequestHeader(name = IdempotentExecutor.IDEMPOTENCY_KEY, required = false) String idempotencyKey,
            @RequestBody SettleOnUsRequest request, HttpServletRequest http) {
        return idempotent.execute(idempotencyKey, http, request, HttpStatus.OK,
                () -> SettlementResponse.of(settlements.settleOnUs(holdId, request.toIban())));
    }

    @GetMapping("/{holdId}")
    HoldResponse get(@PathVariable UUID holdId) {
        return HoldResponse.of(holds.get(holdId));
    }
}
