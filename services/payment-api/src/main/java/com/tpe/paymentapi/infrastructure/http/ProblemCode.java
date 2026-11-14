package com.tpe.paymentapi.infrastructure.http;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.web.client.RestClientResponseException;

/** The {@code code} of a downstream Problem Details body (specs/00-engineering-baseline.md §7). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ProblemCode(String code) {

    /** The error answer's {@code code}; {@code null} for a body that is none or unreadable. */
    public static String of(RestClientResponseException e) {
        try {
            ProblemCode problem = e.getResponseBodyAs(ProblemCode.class);
            return problem == null ? null : problem.code();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }
}
