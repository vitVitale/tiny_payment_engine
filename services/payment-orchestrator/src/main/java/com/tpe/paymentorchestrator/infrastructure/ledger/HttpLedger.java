package com.tpe.paymentorchestrator.infrastructure.ledger;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tpe.paymentorchestrator.application.ledger.Ledger;
import com.tpe.paymentorchestrator.application.ledger.LedgerAnswer;
import com.tpe.paymentorchestrator.application.ledger.LedgerRefusal;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ledger-service's Posting API (specs/12-ledger-service.md §3.1) over HTTP, connect timeout 200 ms and the read
 * timeout the runner computed (specs/00-engineering-baseline.md §8). The hold id is the {@code paymentId}; the
 * {@code Idempotency-Key} is {@code hold-create:<holdId>}, {@code hold-settle-on-us:<holdId>} or
 * {@code hold-release:<holdId>}, so a retry replays the ledger's stored answer.
 *
 * <p>A {@code 4xx} Problem Details with a {@code code} (specs/12-ledger-service.md §3.4) is
 * {@link LedgerAnswer.Refused}, except {@code CONCURRENT_MODIFICATION}, which asks to retry;
 * {@code IDEMPOTENCY_CONFLICT} is a bug on one side and logged as an error. Anything else is
 * {@link LedgerAnswer.Unavailable}: retryable for a connect failure, a read timeout, {@code 502}, {@code 503} and
 * {@code 504}; not for any other status or an unreadable answer, which are logged as errors.
 */
public class HttpLedger implements Ledger {

    static final String HOLDS = "/v1/holds";
    static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    private static final Logger LOG = LoggerFactory.getLogger(HttpLedger.class);

    /** Besides connect failures and read timeouts, what may be retried (specs/00-engineering-baseline.md §8). */
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(502, 503, 504);

    /** Returned once the ledger's own retries are exhausted: the caller retries (specs/12-ledger-service.md §3.4). */
    private static final String CONCURRENT_MODIFICATION = "CONCURRENT_MODIFICATION";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient client;
    private final HttpClient http;

    /** @param client with the base URL and the service's JSON and observation setup */
    public HttpLedger(RestClient client, Duration connectTimeout) {
        this.client = client;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public LedgerAnswer<HoldCreated> createHold(Payment payment, Duration readTimeout) {
        UUID holdId = payment.paymentId().value();
        var request = new CreateHoldRequest(holdId, payment.debtor().iban().value(), payment.amount().minor(),
                payment.amount().currency().getCurrencyCode(),
                new CreateHoldRequest.Reference(holdId, payment.txId().value()));
        return post(payment, "create hold", HOLDS, "hold-create:" + holdId, request, readTimeout, HoldBody.class,
                hold -> hold.accountId() == null ? null : new HoldCreated(hold.accountId()));
    }

    @Override
    public LedgerAnswer<SettledOnUs> settleOnUs(Payment payment, Duration readTimeout) {
        UUID holdId = payment.paymentId().value();
        return post(payment, "settle on-us", HOLDS + "/" + holdId + "/settle-on-us", "hold-settle-on-us:" + holdId,
                new SettleOnUsRequest(payment.creditor().iban().value()), readTimeout, SettlementBody.class,
                settled -> settled.journalEntryId() == null || settled.creditorAccountId() == null ? null
                        : new SettledOnUs(settled.journalEntryId(), settled.creditorAccountId()));
    }

    @Override
    public LedgerAnswer<Released> releaseHold(Payment payment, Duration readTimeout) {
        UUID holdId = payment.paymentId().value();
        return post(payment, "release hold", HOLDS + "/" + holdId + "/release", "hold-release:" + holdId, Map.of(),
                readTimeout, ReleaseBody.class, released -> "RELEASED".equals(released.status()) ? new Released()
                        : null);
    }

    /** @param read the answer's value, {@code null} if the body lacks what the orchestrator needs */
    private <B, T> LedgerAnswer<T> post(Payment payment, String call, String path, String idempotencyKey,
            Object request, Duration readTimeout, Class<B> body, Function<B, T> read) {
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        B response;
        try {
            response = client.mutate().requestFactory(factory).build()
                    .post().uri(path).header(IDEMPOTENCY_KEY, idempotencyKey).body(request)
                    .retrieve().body(body);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            String code = status >= 400 && status < 500 ? code(e.getResponseBodyAsString()) : null;
            if (CONCURRENT_MODIFICATION.equals(code)) {
                return new LedgerAnswer.Unavailable<>("ledger-service answered " + code, true);
            }
            if (code != null) {
                // one line per call outcome other than success (specs/26-observability.md §4); a conflict is a bug
                if (LedgerRefusal.IDEMPOTENCY_CONFLICT.equals(code)) {
                    LOG.error("ledger-service refused {} for payment {}: {} {}", call, payment.paymentId().value(),
                            status, code);
                } else {
                    LOG.info("ledger-service refused {} for payment {}: {} {}", call, payment.paymentId().value(),
                            status, code);
                }
                return new LedgerAnswer.Refused<>(new LedgerRefusal(code));
            }
            boolean retryable = RETRYABLE_STATUS.contains(status);
            if (!retryable) {
                LOG.error("ledger-service answered {} to {} for payment {}", status, call,
                        payment.paymentId().value());
            }
            return new LedgerAnswer.Unavailable<>("ledger-service answered " + status, retryable);
        } catch (ResourceAccessException e) {
            // connect failure or read timeout; the message names hosts and ports only, but stays out of the log
            return new LedgerAnswer.Unavailable<>("ledger-service not reached: " + e.getClass().getSimpleName(), true);
        } catch (RestClientException e) {
            LOG.error("ledger-service answer to {} for payment {} unreadable: {}", call, payment.paymentId().value(),
                    e.getClass().getSimpleName());
            return new LedgerAnswer.Unavailable<>("ledger-service answer unreadable", false);
        }
        T value = response == null ? null : read.apply(response);
        if (value == null) {
            LOG.error("ledger-service answered {} for payment {} without its ids", call, payment.paymentId().value());
            return new LedgerAnswer.Unavailable<>("ledger-service answer incomplete", false);
        }
        return new LedgerAnswer.Done<>(value);
    }

    /** The Problem Details {@code code} (specs/00-engineering-baseline.md §7), or {@code null} without one. */
    private static String code(String problem) {
        try {
            JsonNode code = JSON.readTree(problem).get("code");
            return code != null && code.isString() && !code.asString().isBlank() ? code.asString() : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    /** {@code POST /v1/holds}. */
    record CreateHoldRequest(UUID holdId, String debtorIban, long amountMinor, String currency, Reference reference) {

        record Reference(UUID paymentId, String txId) {}
    }

    /** {@code POST /v1/holds/{holdId}/settle-on-us}. */
    record SettleOnUsRequest(String creditorIban) {}

    /** What the orchestrator reads of a hold. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record HoldBody(UUID accountId) {}

    /** What the orchestrator reads of a release: {@code RELEASED}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record ReleaseBody(String status) {}

    /** What the orchestrator reads of a settlement. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record SettlementBody(UUID journalEntryId, UUID creditorAccountId) {}
}
