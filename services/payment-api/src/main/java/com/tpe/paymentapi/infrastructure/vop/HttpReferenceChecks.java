package com.tpe.paymentapi.infrastructure.vop;

import com.tpe.contracts.vop.ReferenceCheckRequest;
import com.tpe.contracts.vop.ReferenceCheckResponse;
import com.tpe.paymentapi.application.submission.Failure;
import com.tpe.paymentapi.application.submission.ReferenceChecks;
import com.tpe.paymentapi.application.submission.UnexpectedAnswerException;
import com.tpe.paymentapi.infrastructure.http.HttpFailures;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.PartyName;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * vop-service {@code POST /v1/verifications/{vopReference}/check} over HTTP (specs/13-vop-service.md §3.1, 10 §5.3),
 * with the client's connect and read timeouts. {@code 200} is valid with its result or invalid with its problem; a
 * connect failure, a read timeout or a {@code 5xx} is {@link ReferenceChecks.Unavailable}. Any other answer is a bug
 * on one side: logged and thrown.
 */
public class HttpReferenceChecks implements ReferenceChecks {

    static final String CHECK = "/v1/verifications/{vopReference}/check";

    private static final Logger LOG = LoggerFactory.getLogger(HttpReferenceChecks.class);

    private final RestClient client;

    /** @param client with the base URL, the timeouts and the service's JSON and observation setup */
    public HttpReferenceChecks(RestClient client) {
        this.client = client;
    }

    @Override
    public Answer check(String vopReference, Iban debtorIban, Iban payeeIban, PartyName payeeName) {
        ReferenceCheckResponse response;
        try {
            response = client.post().uri(CHECK, vopReference)
                    .body(new ReferenceCheckRequest(debtorIban, payeeIban, payeeName.value()))
                    .retrieve().body(ReferenceCheckResponse.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            Optional<Failure> failure = HttpFailures.of(status);
            if (failure.isPresent()) {
                return new Unavailable(failure.get(), "vop-service answered " + status);
            }
            throw unexpected(String.valueOf(status));
        } catch (ResourceAccessException e) {
            Failure failure = HttpFailures.of(e);
            return new Unavailable(failure, "vop-service not reached: " + failure);
        } catch (RestClientException e) {
            throw unexpected("an unreadable answer");
        }
        if (response != null && response.valid() && response.result() != null) {
            return new Valid(response.result());
        }
        if (response != null && !response.valid() && response.problem() != null) {
            return new Invalid(response.problem());
        }
        throw unexpected("200 without a result or a problem");
    }

    /** The reference is the channel's input and stays out of the log, as do the IBANs and the name. */
    private static UnexpectedAnswerException unexpected(String answer) {
        LOG.error("vop-service answered a reference check with {}", answer);
        return new UnexpectedAnswerException("vop-service answered " + answer);
    }
}
