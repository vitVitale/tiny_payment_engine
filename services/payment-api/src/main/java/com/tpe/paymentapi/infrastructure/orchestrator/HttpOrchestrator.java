package com.tpe.paymentapi.infrastructure.orchestrator;

import com.tpe.contracts.payment.PaymentAccepted;
import com.tpe.contracts.payment.SubmitPayment;
import com.tpe.paymentapi.application.submission.Failure;
import com.tpe.paymentapi.application.submission.Orchestrator;
import com.tpe.paymentapi.application.submission.UnexpectedAnswerException;
import com.tpe.paymentapi.infrastructure.http.HttpFailures;
import com.tpe.paymentapi.infrastructure.http.ProblemCode;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * payment-orchestrator {@code POST /v1/payments} over HTTP (specs/11-payment-orchestrator.md §3.1, 10 §4), with the
 * client's connect and read timeouts. {@code 201} and {@code 200} are the payment; {@code 409 COMMAND_CONFLICT} and
 * {@code 422 CREDITOR_AGENT_NOT_REACHABLE} are answers; a connect failure, a read timeout or a {@code 5xx} is
 * {@link Orchestrator.Unavailable}. Any other answer is a bug on one side: logged and thrown.
 */
public class HttpOrchestrator implements Orchestrator {

    static final String PAYMENTS = "/v1/payments";

    private static final Logger LOG = LoggerFactory.getLogger(HttpOrchestrator.class);

    private final RestClient client;

    /** @param client with the base URL, the timeouts and the service's JSON and observation setup */
    public HttpOrchestrator(RestClient client) {
        this.client = client;
    }

    @Override
    public Answer submit(SubmitPayment command) {
        ResponseEntity<PaymentAccepted> response;
        try {
            response = client.post().uri(PAYMENTS).body(command).retrieve().toEntity(PaymentAccepted.class);
        } catch (RestClientResponseException e) {
            return refused(command, e);
        } catch (ResourceAccessException e) {
            // the message names hosts and ports only, but stays out of the answer
            Failure failure = HttpFailures.of(e);
            return new Unavailable(failure, "payment-orchestrator not reached: " + failure);
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

    private Answer refused(SubmitPayment command, RestClientResponseException e) {
        int status = e.getStatusCode().value();
        Optional<Failure> failure = HttpFailures.of(status);
        if (failure.isPresent()) {
            return new Unavailable(failure.get(), "payment-orchestrator answered " + status);
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

    private static String code(RestClientResponseException e) {
        try {
            ProblemCode problem = e.getResponseBodyAs(ProblemCode.class);
            return problem == null ? null : problem.code();
        } catch (RuntimeException unreadable) {
            return null;
        }
    }

    private static UnexpectedAnswerException unexpected(SubmitPayment command, String answer) {
        LOG.error("payment-orchestrator answered commandId {} with {}", command.commandId(), answer);
        return new UnexpectedAnswerException("payment-orchestrator answered " + answer);
    }
}
