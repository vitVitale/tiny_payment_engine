package com.tpe.paymentapi.infrastructure.orchestrator;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.tpe.contracts.payment.PaymentAccepted;
import com.tpe.contracts.payment.PaymentState;
import com.tpe.contracts.payment.SubmitPayment;
import com.tpe.paymentapi.application.submission.Failure;
import com.tpe.paymentapi.application.submission.Orchestrator;
import com.tpe.paymentapi.application.submission.UnexpectedAnswerException;
import com.tpe.paymentapi.infrastructure.http.HttpFailures;
import com.tpe.paymentapi.infrastructure.http.ProblemCode;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.TxId;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * payment-orchestrator {@code POST /v1/payments} and {@code GET /v1/payments/{paymentId}} over HTTP
 * (specs/11-payment-orchestrator.md §3.1, 10 §4), each with its client's connect and read timeouts. A submit's
 * {@code 201} and {@code 200} are the payment; {@code 409 COMMAND_CONFLICT} and
 * {@code 422 CREDITOR_AGENT_NOT_REACHABLE} are answers. A status read is the payment's state or {@code 404 PAYMENT_NOT_FOUND}. For both, a connect failure, a
 * read timeout or a {@code 5xx} is {@link Orchestrator.Unavailable}; any other answer is a bug on one side: logged and
 * thrown.
 */
public class HttpOrchestrator implements Orchestrator {

    static final String PAYMENTS = "/v1/payments";

    private static final Logger LOG = LoggerFactory.getLogger(HttpOrchestrator.class);

    private final RestClient client;
    private final RestClient status;

    /**
     * @param client the submit's: the base URL, its timeouts and the service's JSON and observation setup
     * @param status the status read's, with its own read timeout (10 §4)
     */
    public HttpOrchestrator(RestClient client, RestClient status) {
        this.client = client;
        this.status = status;
    }

    @Override
    public Answer submit(SubmitPayment command) {
        ResponseEntity<PaymentAccepted> response;
        try {
            response = client.post().uri(PAYMENTS).body(command).retrieve().toEntity(PaymentAccepted.class);
        } catch (RestClientResponseException e) {
            return refused(command, e);
        } catch (ResourceAccessException e) {
            return notReached(e);
        } catch (RestClientException e) {
            throw unexpected(command, "an unreadable answer");
        }
        int status = response.getStatusCode().value();
        PaymentAccepted payment = response.getBody();
        if (status != 200 && status != 201 || payment == null || payment.paymentId() == null
                || payment.state() == null || payment.acceptedAt() == null) {
            throw unexpected(command, status + " without the payment");
        }
        return new Accepted(payment, status == 201);
    }

    @Override
    public Read payment(PaymentId paymentId) {
        PaymentStatus payment;
        try {
            payment = status.get().uri(PAYMENTS + "/{paymentId}", paymentId.value()).retrieve()
                    .body(PaymentStatus.class);
        } catch (RestClientResponseException e) {
            int code = e.getStatusCode().value();
            Optional<Unavailable> unavailable = answered(code);
            if (unavailable.isPresent()) {
                return unavailable.get();
            }
            if (code == 404 && "PAYMENT_NOT_FOUND".equals(code(e))) {
                return new NotFound();
            }
            throw unexpected(paymentId, code + " " + code(e));
        } catch (ResourceAccessException e) {
            return notReached(e);
        } catch (RestClientException e) {
            throw unexpected(paymentId, "an unreadable answer");
        }
        if (payment == null || !paymentId.equals(payment.paymentId()) || payment.state() == null) {
            throw unexpected(paymentId, "200 without the payment");
        }
        return new Found(payment.state(), payment.txId());
    }

    /** What payment-api reads of {@code PaymentView} (P-01). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record PaymentStatus(PaymentId paymentId, PaymentState state, TxId txId) {}

    private Answer refused(SubmitPayment command, RestClientResponseException e) {
        int status = e.getStatusCode().value();
        Optional<Unavailable> unavailable = answered(status);
        if (unavailable.isPresent()) {
            return unavailable.get();
        }
        String code = code(e);
        if (status == 409 && "COMMAND_CONFLICT".equals(code)) {
            return new CommandConflict();
        }
        if (status == 422 && "CREDITOR_AGENT_NOT_REACHABLE".equals(code)) {
            return new CreditorAgentNotReachable();
        }
        throw unexpected(command, status + " " + code);
    }

    /** The message names hosts and ports only, but stays out of the answer. */
    private static Unavailable notReached(ResourceAccessException e) {
        Failure failure = HttpFailures.of(e);
        return new Unavailable(failure, "payment-orchestrator not reached: " + failure);
    }

    /** A {@code 5xx} as {@link Unavailable}; empty for any other status. */
    private static Optional<Unavailable> answered(int status) {
        return HttpFailures.of(status)
                .map(failure -> new Unavailable(failure, "payment-orchestrator answered " + status));
    }

    private static String code(RestClientResponseException e) {
        try {
            ProblemCode problem = e.getResponseBodyAs(ProblemCode.class);
            return problem == null ? null : problem.code();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static UnexpectedAnswerException unexpected(PaymentId paymentId, String answer) {
        LOG.error("payment-orchestrator answered the status of payment {} with {}", paymentId.value(), answer);
        return new UnexpectedAnswerException("payment-orchestrator answered " + answer);
    }

    private static UnexpectedAnswerException unexpected(SubmitPayment command, String answer) {
        LOG.error("payment-orchestrator answered commandId {} with {}", command.commandId(), answer);
        return new UnexpectedAnswerException("payment-orchestrator answered " + answer);
    }
}
