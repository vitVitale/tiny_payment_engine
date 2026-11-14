package com.tpe.paymentapi.api;

import static com.tpe.paymentapi.api.Problems.problem;

import com.tpe.paymentapi.api.payment.IdempotencyKeyException;
import com.tpe.paymentapi.api.security.DebtorAccountNotOwnedException;
import com.tpe.paymentapi.api.security.InvalidTokenClaimsException;
import com.tpe.paymentapi.application.submission.IdempotencyKeyReusedException;
import com.tpe.paymentapi.application.submission.OrchestratorUnavailableException;
import com.tpe.paymentapi.application.submission.UnexpectedAnswerException;
import com.tpe.paymentapi.application.submission.VopRequiredException;
import com.tpe.paymentapi.application.submission.VopUnavailableException;
import com.tpe.paymentapi.application.validation.CreditorAgentNotReachableException;
import com.tpe.paymentapi.application.validation.ValidationFailedException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Errors as RFC 9457 Problem Details with {@code code} and, for {@code 400 VALIDATION_FAILED}, {@code errors}
 * (specs/00-engineering-baseline.md §7, specs/10-payment-api.md §3.5). Every {@code 400} Spring MVC raises itself (an
 * unreadable body) is {@code VALIDATION_FAILED} too. Neither the detail nor the log carries a value of the request.
 */
@RestControllerAdvice
class ApiProblems extends ResponseEntityExceptionHandler {

    static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    /** The client may retry with the same {@code Idempotency-Key} after this many seconds (UC-01 5a). */
    static final String RETRY_AFTER_SECONDS = "1";

    private static final Logger LOG = LoggerFactory.getLogger(ApiProblems.class);

    @ExceptionHandler
    ProblemDetail validationFailed(ValidationFailedException e) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, "Request validation failed");
        problem.setProperty("errors", e.errors());
        return problem;
    }

    @ExceptionHandler
    ProblemDetail idempotencyKey(IdempotencyKeyException e) {
        return problem(HttpStatus.BAD_REQUEST, e.code(), e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail debtorAccountNotOwned(DebtorAccountNotOwnedException e) {
        return problem(HttpStatus.FORBIDDEN, "DEBTOR_ACCOUNT_NOT_OWNED",
                "The debtor account is not one of the token's accounts");
    }

    @ExceptionHandler
    ProblemDetail invalidTokenClaims(InvalidTokenClaimsException e) {
        return problem(HttpStatus.FORBIDDEN, HttpStatus.FORBIDDEN.name(), e.getMessage());
    }

    @ExceptionHandler
    ProblemDetail creditorAgentNotReachable(CreditorAgentNotReachableException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "CREDITOR_AGENT_NOT_REACHABLE",
                "The creditor's bank is not reachable for SCT Inst");
    }

    @ExceptionHandler
    ProblemDetail vopRequired(VopRequiredException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "VOP_REQUIRED", e.problem());
    }

    /** Without the stored-response guard it is the orchestrator that finds the reuse (UC-01 2b). */
    @ExceptionHandler
    ProblemDetail idempotencyKeyReused(IdempotencyKeyReusedException e) {
        LOG.warn("422 IDEMPOTENCY_KEY_REUSED: {}", e.getMessage());
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "IDEMPOTENCY_KEY_REUSED",
                "The Idempotency-Key was used for another request");
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> orchestratorUnavailable(OrchestratorUnavailableException e) {
        return unavailable("ORCHESTRATOR_UNAVAILABLE", "The payment could not be submitted; retry with the same "
                + "Idempotency-Key");
    }

    @ExceptionHandler
    ResponseEntity<ProblemDetail> vopUnavailable(VopUnavailableException e) {
        return unavailable("VOP_UNAVAILABLE", "The VoP reference could not be checked; retry with the same "
                + "Idempotency-Key");
    }

    /** A bug on one side, logged where it was found. */
    @ExceptionHandler
    ProblemDetail unexpectedAnswer(UnexpectedAnswerException e) {
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error");
    }

    private static ResponseEntity<ProblemDetail> unavailable(String code, String detail) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, code, detail));
    }

    /** Every other problem Spring MVC raises gets a {@code code} too: {@code VALIDATION_FAILED} or the status name. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            HttpStatus known = HttpStatus.resolve(status.value());
            String code = known == HttpStatus.BAD_REQUEST ? VALIDATION_FAILED
                    : known == null ? "HTTP_" + status.value() : known.name();
            problem.setType(Problems.type(code));
            problem.setProperty("code", code);
            if (code.equals(VALIDATION_FAILED)
                    && (problem.getProperties() == null || !problem.getProperties().containsKey("errors"))) {
                problem.setProperty("errors", List.of());
            }
        }
        return super.handleExceptionInternal(e, body, headers, status, request);
    }
}
