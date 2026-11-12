package com.tpe.vopservice.api;

import static com.tpe.vopservice.api.Problems.problem;

import com.tpe.paymentdomain.DomainValidationException;
import com.tpe.vopservice.application.requesting.PayeeBankUnknownException;
import com.tpe.vopservice.application.responding.UnknownParticipantException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import tools.jackson.core.JacksonException;

/**
 * Errors as RFC 9457 Problem Details with {@code code} and, for {@code 400}, {@code errors}
 * (specs/00-engineering-baseline.md §7), with the codes of specs/13-vop-service.md §3. Every {@code 400} Spring MVC
 * raises itself (unreadable body) is {@code VALIDATION_FAILED} too; a value object of {@code contracts} that refuses
 * its JSON value (an invalid IBAN) is reported as that field with the value object's code.
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
    ProblemDetail unknownParticipant(UnknownParticipantException e) {
        return problem(HttpStatus.UNAUTHORIZED, "UNKNOWN_PARTICIPANT",
                "The requesting PSP is not a participant of the VoP scheme");
    }

    @ExceptionHandler
    ProblemDetail payeeBankUnknown(PayeeBankUnknownException e) {
        return problem(HttpStatus.UNPROCESSABLE_CONTENT, "PAYEE_BANK_UNKNOWN",
                "The payee's bank is not in the bank directory");
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        Optional<FieldError> invalidValue = invalidValue(e);
        if (invalidValue.isEmpty()) {
            return super.handleHttpMessageNotReadable(e, headers, status, request);
        }
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, "Request validation failed");
        problem.setProperty("errors", List.of(invalidValue.get()));
        return handleExceptionInternal(e, problem, headers, status, request);
    }

    /** The JSON path of the value a value object refused, and its validation code; never the value itself. */
    private static Optional<FieldError> invalidValue(Throwable e) {
        JacksonException jackson = null;
        DomainValidationException domain = null;
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof JacksonException found && jackson == null) {
                jackson = found;
            }
            if (cause instanceof DomainValidationException found) {
                domain = found;
            }
        }
        if (jackson == null || domain == null || jackson.getPath().isEmpty()) {
            return Optional.empty();
        }
        String field = jackson.getPath().stream()
                .map(step -> step.getPropertyName() != null ? step.getPropertyName() : "[" + step.getIndex() + "]")
                .collect(Collectors.joining("."));
        return Optional.of(new FieldError(field, domain.code().name()));
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
