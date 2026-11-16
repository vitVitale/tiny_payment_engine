package com.tpe.csmgateway.api;

import com.tpe.paymentdomain.DomainValidationException;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Problem Details with {@code code} and, on a {@code 400}, {@code errors} (specs/00-engineering-baseline.md §7):
 * {@code INVALID_ENVELOPE} (the CSM transport), {@code VALIDATION_FAILED} (a value the admin API cannot read, and every
 * other {@code 400} Spring MVC raises), {@code NOT_FOUND}, else the status name.
 */
@RestControllerAdvice
class ApiProblems extends ResponseEntityExceptionHandler {

    static final String INVALID_ENVELOPE = "INVALID_ENVELOPE";
    static final String VALIDATION_FAILED = "VALIDATION_FAILED";

    /** A path or query value a value object refused, e.g. a {@code txId} or a BIC. */
    @ExceptionHandler(DomainValidationException.class)
    ProblemDetail domainValidation(DomainValidationException e) {
        return Problems.badRequest(VALIDATION_FAILED, e.getMessage(),
                List.of(new Problems.FieldError(null, e.code().name())));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        // Spring MVC's own exceptions (a missing parameter, say) arrive without a body: theirs is the problem
        if (body == null && e instanceof ErrorResponse response) {
            body = response.getBody();
        }
        if (body instanceof ProblemDetail problem) {
            HttpStatus known = HttpStatus.resolve(status.value());
            String code = known == HttpStatus.BAD_REQUEST ? VALIDATION_FAILED
                    : known == null ? "HTTP_" + status.value() : known.name();
            problem.setType(Problems.type(code));
            problem.setProperty("code", code);
            Map<String, Object> properties = problem.getProperties();
            if (code.equals(VALIDATION_FAILED) && (properties == null || !properties.containsKey("errors"))) {
                problem.setProperty("errors", List.of());
            }
        }
        return super.handleExceptionInternal(e, body, headers, status, request);
    }
}
