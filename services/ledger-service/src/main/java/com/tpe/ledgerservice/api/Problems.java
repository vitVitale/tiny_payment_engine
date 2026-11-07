package com.tpe.ledgerservice.api;

import com.tpe.ledgerservice.domain.LedgerError;
import com.tpe.ledgerservice.domain.LedgerException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

/**
 * RFC 9457 Problem Details with the {@code code} extension (specs/00-engineering-baseline.md §7), for the MVC advice,
 * the idempotency executor, which stores them, and the security filters, which answer before any controller.
 */
public final class Problems {

    private Problems() {}

    public static ProblemDetail problem(HttpStatus status, String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type(code));
        problem.setProperty("code", code);
        return problem;
    }

    /** A business refusal with the status of specs/12-ledger-service.md §3.4. */
    public static ProblemDetail refused(LedgerException e) {
        return problem(status(e.error()), e.error().name(), e.getMessage());
    }

    /**
     * A refusal of a mutating request (§3.4): {@code ACCOUNT_NOT_FOUND} is {@code 422} there, a deterministic outcome
     * the idempotency guard stores; only reads answer it with {@code 404}.
     */
    public static ProblemDetail refusedChange(LedgerException e) {
        HttpStatus status = e.error() == LedgerError.ACCOUNT_NOT_FOUND
                ? HttpStatus.UNPROCESSABLE_CONTENT
                : status(e.error());
        return problem(status, e.error().name(), e.getMessage());
    }

    /** The statuses of §3.4 on reads: {@code ACCOUNT_NOT_FOUND} is {@code 404}. */
    private static HttpStatus status(LedgerError error) {
        return switch (error) {
            case ACCOUNT_NOT_FOUND, HOLD_NOT_FOUND -> HttpStatus.NOT_FOUND;
            case ACCOUNT_NOT_CUSTOMER, ACCOUNT_CLOSED, ACCOUNT_BLOCKED, CUSTOMER_ID_MISSING, INSUFFICIENT_FUNDS,
                    CURRENCY_NOT_SUPPORTED -> HttpStatus.UNPROCESSABLE_CONTENT;
            case SANCTIONS_BLOCK_PROTECTED, FORBIDDEN -> HttpStatus.FORBIDDEN;
            case HOLD_RELEASED, HOLD_SETTLED, IDEMPOTENCY_CONFLICT, CONCURRENT_MODIFICATION -> HttpStatus.CONFLICT;
        };
    }

    /** {@code CUSTOMER_ID_MISSING} → {@code https://tpe.example/problems/customer-id-missing}. */
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
