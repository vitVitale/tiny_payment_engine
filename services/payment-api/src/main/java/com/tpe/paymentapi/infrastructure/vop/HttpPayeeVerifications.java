package com.tpe.paymentapi.infrastructure.vop;

import com.tpe.contracts.vop.VerificationRequest;
import com.tpe.contracts.vop.VerificationResponse;
import com.tpe.paymentapi.application.submission.Failure;
import com.tpe.paymentapi.application.submission.UnexpectedAnswerException;
import com.tpe.paymentapi.application.verification.PayeeVerifications;
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
 * vop-service {@code POST /v1/verifications} over HTTP (specs/13-vop-service.md §3.1, 10 §3.4), with the client's
 * connect timeout and the 5.5 s read timeout. {@code 201} is the verification; {@code 422 PAYEE_BANK_UNKNOWN} is an
 * answer; a connect failure, a read timeout or a {@code 5xx} is {@link PayeeVerifications.Unavailable}. Any other
 * answer is a bug on one side (payment-api checks every field vop-service does): logged and thrown.
 */
public class HttpPayeeVerifications implements PayeeVerifications {

    static final String VERIFICATIONS = "/v1/verifications";

    private static final Logger LOG = LoggerFactory.getLogger(HttpPayeeVerifications.class);

    private final RestClient client;

    /** @param client with the base URL, the timeouts and the service's JSON and observation setup */
    public HttpPayeeVerifications(RestClient client) {
        this.client = client;
    }

    @Override
    public Answer verify(VerificationRequest request) {
        ResponseEntity<VerificationResponse> response;
        try {
            response = client.post().uri(VERIFICATIONS).body(request).retrieve()
                    .toEntity(VerificationResponse.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            Optional<Failure> failure = HttpFailures.of(status);
            if (failure.isPresent()) {
                return new Unavailable(failure.get(), "vop-service answered " + status);
            }
            String code = ProblemCode.of(e);
            if (status == 422 && "PAYEE_BANK_UNKNOWN".equals(code)) {
                return new PayeeBankUnknown();
            }
            throw unexpected(status + " " + code);
        } catch (ResourceAccessException e) {
            Failure failure = HttpFailures.of(e);
            return new Unavailable(failure, "vop-service not reached: " + failure);
        } catch (RestClientException e) {
            throw unexpected("an unreadable answer");
        }
        VerificationResponse verification = response.getBody();
        if (response.getStatusCode().value() != 201 || verification == null || verification.vopReference() == null
                || verification.result() == null || verification.expiresAt() == null) {
            throw unexpected(response.getStatusCode().value() + " without a verification");
        }
        return new Verified(verification);
    }

    /** The IBANs and the name are the channel's input and stay out of the log. */
    private static UnexpectedAnswerException unexpected(String answer) {
        LOG.error("vop-service answered a verification with {}", answer);
        return new UnexpectedAnswerException("vop-service answered " + answer);
    }
}
