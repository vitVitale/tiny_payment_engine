package com.tpe.csmgateway.api.csm;

import com.tpe.csmgateway.api.Problems;
import com.tpe.csmgateway.application.correlation.StatusReportCorrelation;
import com.tpe.csmgateway.application.correlation.StatusReportCorrelation.Unparsable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The CSM inbound endpoint, {@code POST /csm/v1/inbound} (specs/15-csm-gateway.md §3.1): {@code 202} once the message
 * is journalled, a transport acknowledgement only; {@code 400 INVALID_ENVELOPE} only if the envelope cannot be parsed
 * at all.
 */
@RestController
class CsmInboundController {

    private final StatusReportCorrelation correlation;

    CsmInboundController(StatusReportCorrelation correlation) {
        this.correlation = correlation;
    }

    /** The body is read as bytes: XML without a charset is UTF-8. */
    @Operation(summary = "A message from the CSM (envelope with pacs.002 or pacs.008)")
    @ApiResponse(responseCode = "202", description = "Journalled", content = @Content)
    @ApiResponse(responseCode = "400", description = "The envelope cannot be parsed",
            content = @Content(mediaType = "application/problem+json",
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(path = "/csm/v1/inbound", consumes = {"application/xml", "text/xml"})
    ResponseEntity<ProblemDetail> receive(@RequestBody byte[] body) {
        if (correlation.receive(new String(body, StandardCharsets.UTF_8)) instanceof Unparsable unparsable) {
            // the codes only: a violation message may quote the input
            return ResponseEntity.badRequest().body(Problems.badRequest("INVALID_ENVELOPE", "unparsable envelope",
                    unparsable.violations().stream().map(v -> new Problems.FieldError("ENVELOPE", v.code()))
                            .distinct().toList()));
        }
        return ResponseEntity.accepted().build();
    }
}
