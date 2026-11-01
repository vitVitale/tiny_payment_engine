package com.tpe.ledgerservice.api;

import static com.tpe.ledgerservice.api.Problems.problem;

import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import java.util.List;
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
 * Errors as RFC 9457 Problem Details with {@code code} and, for {@code 400}, {@code errors}
 * (specs/00-engineering-baseline.md §7) and the codes of specs/12-ledger-service.md §3.4. Every {@code 400} Spring MVC
 * raises itself (unreadable body, bad path variable, missing parameter) is {@code VALIDATION_FAILED} too.
 */
@RestControllerAdvice
class ApiProblems extends ResponseEntityExceptionHandler {

    static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    @ExceptionHandler
    ProblemDetail validationFailed(ValidationFailedException e) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, "Request validation failed");
        problem.setProperty("errors", e.errors());
        return problem;
    }

    @ExceptionHandler
    ProblemDetail refused(LedgerException e) {
        return problem(status(e.error()), e.error().name(), e.getMessage());
    }

    /**
     * The Account API's statuses; {@code ACCOUNT_NOT_FOUND} is {@code 404} on its reads (§3.4) and will be
     * {@code 422} on the Posting API.
     */
    private static HttpStatus status(LedgerError error) {
        return switch (error) {
            case ACCOUNT_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ACCOUNT_NOT_CUSTOMER, CUSTOMER_ID_MISSING -> HttpStatus.UNPROCESSABLE_CONTENT;
        };
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
            if (code.equals(VALIDATION_FAILED)) {
                problem.setProperty("errors", List.of());
            }
        }
        return super.handleExceptionInternal(e, body, headers, status, request);
    }
}
