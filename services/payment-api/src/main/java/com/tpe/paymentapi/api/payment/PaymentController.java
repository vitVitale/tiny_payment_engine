package com.tpe.paymentapi.api.payment;

import com.tpe.contracts.payment.PaymentAccepted;
import com.tpe.iso20022model.XsdValidator;
import com.tpe.paymentapi.api.security.Caller;
import com.tpe.paymentapi.application.idempotency.IdempotencyGuard;
import com.tpe.paymentapi.application.idempotency.IdempotentRequest;
import com.tpe.paymentapi.application.mapping.Pain001Reader;
import com.tpe.paymentapi.application.rejection.FileRejections;
import com.tpe.paymentapi.application.submission.PaymentSubmission;
import com.tpe.paymentapi.application.validation.FieldError;
import com.tpe.paymentapi.application.validation.PaymentInstruction;
import com.tpe.paymentapi.application.validation.PaymentValidator;
import com.tpe.paymentapi.application.validation.ValidatedPayment;
import com.tpe.paymentapi.application.validation.ValidationFailedException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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
 * The payment controller (specs/10-payment-api.md §3.1, UC-01): a channel initiates a payment, as JSON or as a
 * {@code pain.001.001.09}. The processing order of §3.1: the token (security filter chain), debtor ownership, the
 * {@code Idempotency-Key} and its guard (§5.1), then for a new key validation, the VoP reference check and the submit.
 * A {@code pain.001} is read first, as a JSON body is: an unsupported variant is
 * {@code 400 UNSUPPORTED_MEDIA_TYPE_VARIANT}; a schema violation, and a field error of the document read, are
 * {@code 400 VALIDATION_FAILED} with the {@code rejectionId} of a {@code pain.002 RJCT FF01}. The request body is never
 * logged.
 */
@RestController
class PaymentController {

    static final String PAYMENTS = "/v1/payments";
    /** The header of a {@code pain.001}'s VoP reference: no {@code pain.001} element carries it (§3.1). */
    static final String VOP_REFERENCE = "VoP-Reference";

    private static final Logger LOG = LoggerFactory.getLogger(PaymentController.class);

    private final PaymentValidator validator;
    private final PaymentSubmission submission;
    private final IdempotencyGuard guard;
    private final Pain001Reader pain001;
    private final FileRejections rejections;
    private final RequestHash hash;

    PaymentController(PaymentValidator validator, PaymentSubmission submission, IdempotencyGuard guard,
            Pain001Reader pain001, FileRejections rejections, JsonMapper json) {
        this.validator = validator;
        this.submission = submission;
        this.guard = guard;
        this.pain001 = pain001;
        this.rejections = rejections;
        this.hash = new RequestHash(json);
    }

    /** The JSON and the XML operation are one in the OpenAPI document, which springdoc merges by path and method. */
    private static final String INITIATE = "Initiate an SCT Inst payment, as JSON or as pain.001.001.09 (UC-01)";

    @Operation(operationId = "initiate", summary = INITIATE, security = @SecurityRequirement(name = "bearer",
            scopes = "payments:write"))
    @ApiResponse(responseCode = "202", description = "Accepted; Location is the payment's status. The same key and "
            + "body (an XML document: comments aside) again answer the same payment with its current status")
    @ApiResponse(responseCode = "400", description = "VALIDATION_FAILED with errors[] (for XML with the rejectionId "
            + "of a pain.002 RJCT FF01 at /v1/payment-rejections/{rejectionId}/pain.002; a DOCTYPE included), "
            + "UNSUPPORTED_MEDIA_TYPE_VARIANT (XML: not one PmtInf with one CdtTrfTxInf, another version, not INST, "
            + "not today), IDEMPOTENCY_KEY_MISSING, IDEMPOTENCY_KEY_INVALID",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
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
        return accepted(guard.initiate(request, () -> submit(caller, idempotencyKey, instruction)));
    }

    /** Documented with {@link #initiate}: the responses are the same (a 202 here keeps springdoc's default 200 out). */
    @Operation(operationId = "initiate", summary = INITIATE, security = @SecurityRequirement(name = "bearer",
            scopes = "payments:write"))
    @ApiResponse(responseCode = "202", description = "Accepted; Location is the payment's status. The same key and "
            + "body (an XML document: comments aside) again answer the same payment with its current status")
    @PostMapping(path = PAYMENTS, consumes = MediaType.APPLICATION_XML_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<PaymentResponse> initiateXml(
            @Parameter(in = ParameterIn.HEADER, required = true,
                    description = "The same key is the same payment",
                    schema = @Schema(type = "string", pattern = "^[A-Za-z0-9_-]{1,64}$", minLength = 1,
                            maxLength = 64))
            @RequestHeader(name = IdempotencyKeys.HEADER, required = false) String idempotencyKey,
            @Parameter(in = ParameterIn.HEADER, description = "With application/xml: the vopReference of the "
                    + "payee verification (POST /v1/payee-verifications); without it the payment is VOP_REQUIRED")
            @RequestHeader(name = VOP_REFERENCE, required = false) String vopReference,
            @Parameter(hidden = true) @RequestHeader(name = HttpHeaders.CONTENT_TYPE) MediaType contentType,
            @io.swagger.v3.oas.annotations.parameters.RequestBody(required = true, content = @Content(
                    mediaType = MediaType.APPLICATION_XML_VALUE, schema = @Schema(type = "string",
                    description = "pain.001.001.09 with one PmtInf and one CdtTrfTxInf")))
            @RequestBody byte[] body,
            @Parameter(hidden = true) @AuthenticationPrincipal Jwt token) {
        Caller caller = Caller.of(token);
        Charset charset = contentType.getCharset() == null ? StandardCharsets.UTF_8 : contentType.getCharset();
        String xml = new String(body, charset);
        Pain001Reader.Pain001 document;
        try {
            document = pain001.read(xml, vopReference);
        } catch (Pain001Reader.SchemaInvalidException e) {
            throw rejected(caller, e.messageId(), e.violations().stream().map(PaymentController::fieldError).toList());
        }
        PaymentInstruction instruction = document.instruction();
        caller.requireOwns(instruction.debtor().iban());
        IdempotencyKeys.require(idempotencyKey);
        var request = new IdempotentRequest(caller.clientId(), idempotencyKey,
                hash.ofXml("POST", PAYMENTS, xml, vopReference));
        try {
            return accepted(guard.initiate(request, () -> {
                pain001.requireToday(document);
                return submit(caller, idempotencyKey, instruction);
            }));
        } catch (ValidationFailedException e) {
            throw rejected(caller, document.messageId(), e.errors());
        }
    }

    private PaymentAccepted submit(Caller caller, String idempotencyKey, PaymentInstruction instruction) {
        ValidatedPayment payment = validator.validate(instruction);
        return submission.submit(caller.clientId(), idempotencyKey, payment);
    }

    private static ResponseEntity<PaymentResponse> accepted(PaymentAccepted accepted) {
        return ResponseEntity.accepted()
                .location(URI.create(PAYMENTS + "/" + accepted.paymentId().value()))
                .body(PaymentResponse.of(accepted));
    }

    private XmlRejectedException rejected(Caller caller, String originalMessageId, List<FieldError> errors) {
        UUID rejectionId = rejections.reject(caller.clientId(), originalMessageId);
        LOG.info("pain.001 of client {} rejected FF01 as {}: {}", caller.clientId(), rejectionId,
                errors.stream().map(FieldError::code).toList());
        return new XmlRejectedException(rejectionId, errors);
    }

    /** At the violation's position; its message may quote the document and is left out. */
    private static FieldError fieldError(XsdValidator.Violation violation) {
        return new FieldError("line " + violation.line() + ", column " + violation.column(), violation.code());
    }
}
