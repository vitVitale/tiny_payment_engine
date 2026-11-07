package com.tpe.ledgerservice.api;

import com.tpe.ledgerservice.application.idempotency.IdempotencyGuard;
import com.tpe.ledgerservice.application.idempotency.IdempotentRequest;
import com.tpe.ledgerservice.application.idempotency.StoredResponse;
import com.tpe.ledgerservice.domain.LedgerException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs a mutating endpoint through the {@link IdempotencyGuard} (specs/12-ledger-service.md §6.1): checks the
 * {@code Idempotency-Key}, fingerprints the request, and turns the endpoint's outcome into the response the guard
 * stores and replays — the success body, or the Problem Details of a {@code 422} refusal. Any other exception
 * propagates to {@link ApiProblems} unstored. The body is written as stored, so a first response and its replays are
 * the same JSON. Replays carry no headers beyond the content type: only status and body are stored.
 */
@Component
public class IdempotentExecutor {

    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private final IdempotencyGuard guard;
    private final RequestFingerprint fingerprint;
    private final JsonMapper json;

    public IdempotentExecutor(IdempotencyGuard guard, RequestFingerprint fingerprint, JsonMapper json) {
        this.guard = guard;
        this.fingerprint = fingerprint;
        this.json = json;
    }

    /**
     * @param key the {@code Idempotency-Key} header, {@code null} when absent
     * @param http the request; its path is part of the fingerprint, so the same key on another resource conflicts
     * @param request the request body as the endpoint read it
     * @param success the status of a successful outcome
     * @param action validation and the business change; returns the success body
     */
    public ResponseEntity<String> execute(String key, HttpServletRequest http, Object request, HttpStatus success,
            Supplier<?> action) {
        if (key == null || key.isBlank()) {
            throw new IdempotencyKeyMissingException();
        }
        if (key.length() > IdempotentRequest.MAX_KEY_LENGTH) {
            throw new ValidationFailedException(List.of(new FieldError(IDEMPOTENCY_KEY, "TOO_LONG")));
        }
        String path = http.getRequestURI();
        var idempotent = new IdempotentRequest(key, fingerprint.of(path, request));
        StoredResponse response = guard.execute(idempotent, () -> {
            try {
                return new StoredResponse(success.value(), json.writeValueAsString(action.get()));
            } catch (LedgerException e) {
                ProblemDetail problem = Problems.refused(e);
                if (problem.getStatus() != HttpStatus.UNPROCESSABLE_CONTENT.value()) {
                    throw e;
                }
                return new StoredResponse(problem.getStatus(), json.writeValueAsString(Problems.body(problem, path)));
            }
        });
        return ResponseEntity.status(response.status())
                .contentType(response.isSuccess() ? MediaType.APPLICATION_JSON : MediaType.APPLICATION_PROBLEM_JSON)
                .body(response.body());
    }
}
