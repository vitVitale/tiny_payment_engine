package com.tpe.paymentapi.api.payment;

import com.tpe.paymentapi.api.security.Caller;
import com.tpe.paymentapi.application.rejection.FileRejections;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * The {@code pain.002 RJCT FF01} of a {@code pain.001} that failed validation (specs/10-payment-api.md §3.5, UC-01
 * 3a), for the client that sent it, for 24 hours. Another client's, an expired or an unknown rejection is
 * {@code 404} alike.
 */
@RestController
class PaymentRejectionController {

    static final String REJECTIONS = "/v1/payment-rejections";

    private final FileRejections rejections;

    PaymentRejectionController(FileRejections rejections) {
        this.rejections = rejections;
    }

    @Operation(operationId = "rejectionPain002", summary = "The pain.002.001.10 RJCT FF01 of a rejected pain.001 (UC-01 3a)",
            security = @SecurityRequirement(name = "bearer", scopes = "payments:read"))
    @ApiResponse(responseCode = "200", description = "pain.002.001.10 with GrpSts RJCT and reason FF01",
            content = @Content(mediaType = MediaType.APPLICATION_XML_VALUE, schema = @Schema(type = "string")))
    @ApiResponse(responseCode = "404", description = "REJECTION_NOT_FOUND: unknown, expired, or not the caller's",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
            schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(path = REJECTIONS + "/{rejectionId}/pain.002", produces = MediaType.APPLICATION_XML_VALUE)
    String pain002(@PathVariable String rejectionId, @Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        return rejections.document(Caller.of(token).clientId(), rejectionId);
    }
}
