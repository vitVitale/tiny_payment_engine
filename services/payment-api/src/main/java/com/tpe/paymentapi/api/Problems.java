package com.tpe.paymentapi.api;

import java.net.URI;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/** RFC 9457 Problem Details with the {@code code} extension (specs/00-engineering-baseline.md §7). */
public final class Problems {

    private Problems() {}

    public static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type(code));
        problem.setProperty("code", code);
        return problem;
    }

    /** {@code VALIDATION_FAILED} → {@code https://tpe.example/problems/validation-failed}. */
    public static URI type(String code) {
        return URI.create("https://tpe.example/problems/" + code.toLowerCase(Locale.ROOT).replace('_', '-'));
    }
}
