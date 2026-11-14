package com.tpe.paymentorchestrator.infrastructure.risk;

import com.tpe.contracts.Direction;
import com.tpe.contracts.Party;
import com.tpe.contracts.risk.AssessmentRequest;
import com.tpe.contracts.risk.AssessmentResponse;
import com.tpe.contracts.risk.RiskDecision;
import com.tpe.contracts.vop.VopResult;
import com.tpe.paymentorchestrator.application.risk.RiskAnswer;
import com.tpe.paymentorchestrator.application.risk.RiskAssessments;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentDirection;
import com.tpe.paymentorchestrator.domain.payment.PaymentParty;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * risk-service {@code POST /v1/risk/assessments} (specs/14-risk-service.md §3.1) over HTTP, connect timeout 200 ms and
 * the read timeout the runner computed (specs/00-engineering-baseline.md §8). Anything but a decision is
 * {@link RiskAnswer.Unavailable}: retryable for a connect failure, a read timeout, {@code 502}, {@code 503} and
 * {@code 504}; not for any other status, an unreadable answer or a {@code DENY} without its reason, which are bugs on
 * one side and are logged as errors.
 */
public class HttpRiskAssessments implements RiskAssessments {

    static final String PATH = "/v1/risk/assessments";

    private static final Logger LOG = LoggerFactory.getLogger(HttpRiskAssessments.class);

    /** Besides connect failures and read timeouts, what may be retried (specs/00-engineering-baseline.md §8). */
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(502, 503, 504);

    private final RestClient client;
    private final HttpClient http;

    /** @param client with the base URL and the service's JSON and observation setup */
    public HttpRiskAssessments(RestClient client, Duration connectTimeout) {
        this.client = client;
        this.http = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
    }

    @Override
    public RiskAnswer assess(Payment payment, PaymentDirection assessed, Duration readTimeout) {
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(readTimeout);
        Direction direction = Direction.valueOf(assessed.name());
        AssessmentRequest request = new AssessmentRequest(AssessmentRequest.assessmentId(payment.paymentId(),
                direction), payment.paymentId(), direction, payment.txId(), payment.amount(),
                party(payment.debtor()), party(payment.creditor()), payment.debtor().agentBic(),
                payment.creditor().agentBic(), payment.remittance(),
                direction == Direction.OUTGOING ? VopResult.valueOf(payment.vopResult().name()) : null);
        AssessmentResponse response;
        try {
            response = client.mutate().requestFactory(factory).build()
                    .post().uri(PATH).body(request).retrieve().body(AssessmentResponse.class);
        } catch (RestClientResponseException e) {
            int status = e.getStatusCode().value();
            boolean retryable = RETRYABLE_STATUS.contains(status);
            if (!retryable) {
                LOG.error("risk-service answered {} for payment {}", status, payment.paymentId().value());
            }
            return new RiskAnswer.Unavailable("risk-service answered " + status, retryable);
        } catch (ResourceAccessException e) {
            // connect failure or read timeout; the message names hosts and ports only, but stays out of the log
            return new RiskAnswer.Unavailable("risk-service not reached: " + e.getClass().getSimpleName(), true);
        } catch (RestClientException e) {
            LOG.error("risk-service answer for payment {} unreadable: {}", payment.paymentId().value(),
                    e.getClass().getSimpleName());
            return new RiskAnswer.Unavailable("risk-service answer unreadable", false);
        }
        if (response == null || response.decision() == null
                || response.decision() == RiskDecision.DENY && response.reasonCode() == null) {
            LOG.error("risk-service answered payment {} without a decision or a deny reason",
                    payment.paymentId().value());
            return new RiskAnswer.Unavailable("risk-service answer incomplete", false);
        }
        return switch (response.decision()) {
            case ALLOW -> new RiskAnswer.Allow();
            case DENY -> new RiskAnswer.Deny(response.reasonCode());
            case REVIEW -> new RiskAnswer.Review();
        };
    }

    private static Party party(PaymentParty party) {
        return new Party(party.name(), party.iban(), null);
    }
}
