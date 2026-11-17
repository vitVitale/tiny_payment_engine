package com.tpe.csmsimulator.api;

import com.tpe.csmsimulator.application.GatewayUnreachableException;
import com.tpe.paymentdomain.DomainValidationException;
import java.net.URI;
import java.util.List;
import java.util.Locale;
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
 * Problem Details with {@code code}, and {@code errors} on a {@code 400} (specs/00-engineering-baseline.md §7), for
 * the simulator's own APIs. Codes: {@code INVALID_ENVELOPE} (the CSM transport), {@code VALIDATION_FAILED} (a
 * control request it cannot act on, and every other {@code 400} Spring MVC raises), {@code GATEWAY_UNREACHABLE}
 * ({@code 502}: csm-gateway gave no answer to a message the control API sends), else the status name.
 */
@RestControllerAdvice
public class ApiProblems extends ResponseEntityExceptionHandler {

    public static final String INVALID_ENVELOPE = "INVALID_ENVELOPE";
    public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
    public static final String GATEWAY_UNREACHABLE = "GATEWAY_UNREACHABLE";

    /** One entry of {@code errors}. */
    public record FieldError(String field, String code) {}

    /** A {@code 400} problem with {@code code} and {@code errors}. */
    public static ProblemDetail badRequest(String code, String detail, List<FieldError> errors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, detail);
        problem.setType(type(code));
        problem.setProperty("code", code);
        problem.setProperty("errors", List.copyOf(errors));
        return problem;
    }

    /** A value object refused the input: its validation code is the error's. */
    @ExceptionHandler(DomainValidationException.class)
    ProblemDetail domainValidation(DomainValidationException e) {
        return badRequest(VALIDATION_FAILED, e.getMessage(), List.of(new FieldError(null, e.code().name())));
    }

    /** A control value the simulator does not know, such as a scenario it does not play. */
    @ExceptionHandler(InvalidControlRequestException.class)
    ProblemDetail invalidControlRequest(InvalidControlRequestException e) {
        return badRequest(VALIDATION_FAILED, e.getMessage(), List.of(new FieldError(e.field(), "INVALID")));
    }

    @ExceptionHandler(GatewayUnreachableException.class)
    ProblemDetail gatewayUnreachable(GatewayUnreachableException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_GATEWAY, e.getMessage());
        problem.setType(type(GATEWAY_UNREACHABLE));
        problem.setProperty("code", GATEWAY_UNREACHABLE);
        return problem;
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
            problem.setType(type(code));
            problem.setProperty("code", code);
            Map<String, Object> properties = problem.getProperties();
            if (code.equals(VALIDATION_FAILED) && (properties == null || !properties.containsKey("errors"))) {
                problem.setProperty("errors", List.of());
            }
        }
        return super.handleExceptionInternal(e, body, headers, status, request);
    }

    private static URI type(String code) {
        return URI.create("https://tpe.example/problems/" + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
