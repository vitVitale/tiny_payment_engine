package com.tpe.paymentapi.api.payment;

import com.tpe.contracts.payment.PaymentView;
import com.tpe.paymentapi.api.security.Caller;
import com.tpe.paymentapi.application.mapping.PaymentStatusReport;
import com.tpe.paymentapi.application.status.PaymentNotFoundException;
import com.tpe.paymentapi.application.status.PaymentStatuses;
import com.tpe.paymentdomain.PaymentId;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The status of an outgoing payment (specs/10-payment-api.md §3.2, §3.3, UC-07), as JSON or as {@code pain.002}.
 * A payment that is unknown, incoming or not debited from one of the token's accounts is {@code 404} alike, and so is
 * an id that is not a UUID: nothing tells them apart (UC-07 1a).
 */
@RestController
class PaymentStatusController {

    private final PaymentStatuses statuses;
    private final PaymentStatusReport report;

    PaymentStatusController(PaymentStatuses statuses, PaymentStatusReport report) {
        this.statuses = statuses;
        this.report = report;
    }

    @Operation(summary = "The status of a payment (UC-07)", security = @SecurityRequirement(name = "bearer",
            scopes = "payments:read"))
    @ApiResponse(responseCode = "200", description = "The payment's customer status")
    @ApiResponse(responseCode = "404", description = "PAYMENT_NOT_FOUND: unknown, or not the caller's",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "ORCHESTRATOR_UNAVAILABLE; retry after Retry-After",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(path = PaymentController.PAYMENTS + "/{paymentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    PaymentStatusResponse status(@PathVariable String paymentId,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        return PaymentStatusResponse.of(owned(paymentId, token));
    }

    @Operation(summary = "The status of a payment as pain.002.001.10 (UC-07)",
            security = @SecurityRequirement(name = "bearer", scopes = "payments:read"))
    @ApiResponse(responseCode = "200", description = "pain.002.001.10 with GrpSts and TxSts",
            content = @Content(mediaType = MediaType.APPLICATION_XML_VALUE, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "404", description = "PAYMENT_NOT_FOUND: unknown, or not the caller's",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "ORCHESTRATOR_UNAVAILABLE; retry after Retry-After",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(path = PaymentController.PAYMENTS + "/{paymentId}/pain.002",
            produces = MediaType.APPLICATION_XML_VALUE)
    String pain002(@PathVariable String paymentId, @Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        return report.of(owned(paymentId, token));
    }

    private PaymentView owned(String paymentId, Jwt token) {
        Caller caller = Caller.of(token);
        PaymentView payment = statuses.outgoing(id(paymentId));
        if (!caller.owns(payment.debtor().iban())) {
            throw new PaymentNotFoundException();
        }
        return payment;
    }

    private static PaymentId id(String paymentId) {
        try {
            return new PaymentId(UUID.fromString(paymentId));
        } catch (IllegalArgumentException e) {
            throw new PaymentNotFoundException();
        }
    }
}
