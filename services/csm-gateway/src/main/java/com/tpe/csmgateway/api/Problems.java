package com.tpe.csmgateway.api;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** RFC 9457 Problem Details with {@code code}, and {@code errors} on a {@code 400} (specs/00-engineering-baseline.md §7). */
public final class Problems {

    /** One entry of {@code errors}. */
    public record FieldError(String field, String code) {}

    private Problems() {}

    public static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type(code));
        problem.setProperty("code", code);
        return problem;
    }

    public static ProblemDetail badRequest(String code, String detail, List<FieldError> errors) {
        ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, code, detail);
        problem.setProperty("errors", List.copyOf(errors));
        return problem;
    }

    /** {@code INVALID_ENVELOPE} → {@code https://tpe.example/problems/invalid-envelope}. */
    public static URI type(String code) {
        return URI.create("https://tpe.example/problems/" + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    }

    /** The JSON members of {@code problem} as Spring MVC writes them, for code that writes the body itself. */
    public static Map<String, Object> body(ProblemDetail problem, String instance) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", problem.getType().toString());
        body.put("title", problem.getTitle());
        body.put("status", problem.getStatus());
        body.put("detail", problem.getDetail());
        body.put("instance", instance);
        if (problem.getProperties() != null) {
            body.putAll(problem.getProperties());
        }
        return body;
    }
}
