package com.tpe.paymentorchestrator.api;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** RFC 9457 Problem Details with the {@code code} extension (specs/00-engineering-baseline.md §7). */
public final class Problems {

    private Problems() {}

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

    public static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type(code));
        problem.setProperty("code", code);
        return problem;
    }

    /** {@code VALIDATION_FAILED} → {@code https://tpe.example/problems/validation-failed}. */
    static URI type(String code) {
        return URI.create("https://tpe.example/problems/" + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
