package com.tpe.paymentorchestrator.infrastructure.risk;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.tpe.contracts.ContractsModule;
import com.tpe.paymentorchestrator.application.risk.RiskAnswer;
import com.tpe.paymentorchestrator.domain.payment.PayeeVerification;
import com.tpe.paymentorchestrator.domain.payment.Payment;
import com.tpe.paymentorchestrator.domain.payment.PaymentDirection;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

/**
 * The technical failures of {@link HttpRiskAssessments} and whether asking again may help (specs/00-engineering-
 * baseline.md §8); the decisions themselves are P-03 ({@link RiskServicePactTest}).
 */
class HttpRiskAssessmentsTest {

    private static final WireMockServer RISK = new WireMockServer(WireMockConfiguration.options().dynamicPort()
            // HTTP/1.1, as risk-service serves it: Jetty takes the JDK client's h2c upgrade and resets the stream
            .http2PlainDisabled(true));
    /** What the timeout cases run into: short, so their delays stay short. */
    private static final Duration READ_TIMEOUT = Duration.ofMillis(300);
    /**
     * What the cases about the answer itself wait: far beyond any delay of a loaded machine, so the first request of a
     * cold JVM (with the coverage agent on, ~350 ms) never times out and turns into a timeout case.
     */
    private static final Duration GENEROUS = Duration.ofSeconds(10);
    private static final String ALLOW = "{\"decision\":\"ALLOW\",\"reasonCode\":null}";
    private static final Payment PAYMENT = new RiskServicePactTest.Example("0192f0e4-7b1c-7cc3-a1b2-000000000099",
            12_345, 99).payment(PaymentDirection.INCOMING, PayeeVerification.MATCH);

    @BeforeAll
    static void start() {
        RISK.start();
    }

    @AfterAll
    static void stop() {
        RISK.stop();
    }

    @BeforeEach
    void reset() {
        RISK.resetAll();
    }

    @Test
    void anAnswerWithinTheReadTimeoutIsADecision() {
        answer(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(ALLOW));

        assertThat(assess(GENEROUS)).isEqualTo(new RiskAnswer.Allow());
    }

    /** The headers in time, the body not: a read timeout like any other (specs/00-engineering-baseline.md §8). */
    @Test
    void aBodyThatStallsPastTheReadTimeoutIsRetryable() {
        answer(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(ALLOW)
                .withChunkedDribbleDelay(ALLOW.length(), (int) READ_TIMEOUT.multipliedBy(4).toMillis()));

        assertThat(assess(READ_TIMEOUT)).isInstanceOfSatisfying(RiskAnswer.Unavailable.class,
                unavailable -> assertThat(unavailable.retryable()).as(unavailable.detail()).isTrue());
    }

    @Test
    void noHeadersWithinTheReadTimeoutIsRetryable() {
        answer(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(ALLOW)
                .withFixedDelay((int) READ_TIMEOUT.multipliedBy(4).toMillis()));

        assertThat(assess(READ_TIMEOUT)).isInstanceOfSatisfying(RiskAnswer.Unavailable.class,
                unavailable -> assertThat(unavailable.retryable()).as(unavailable.detail()).isTrue());
    }

    /** Asking again would get the same. */
    @Test
    void aBodyThatIsNoAnswerIsNotRetryable() {
        answer(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody("{\"decision\":"));

        assertThat(assess(GENEROUS)).isInstanceOfSatisfying(RiskAnswer.Unavailable.class,
                unavailable -> assertThat(unavailable.retryable()).as(unavailable.detail()).isFalse());
    }

    private static void answer(ResponseDefinitionBuilder response) {
        RISK.stubFor(post(urlEqualTo(HttpRiskAssessments.PATH)).willReturn(response));
    }

    private static RiskAnswer assess(Duration readTimeout) {
        JsonMapper json = JsonMapper.builder().addModule(new ContractsModule()).build();
        var client = new HttpRiskAssessments(RestClient.builder()
                .baseUrl(RISK.baseUrl())
                .configureMessageConverters(converters -> converters.withJsonConverter(
                        new JacksonJsonHttpMessageConverter(json)))
                .build(), Duration.ofSeconds(1));
        return client.assess(PAYMENT, PaymentDirection.INCOMING, readTimeout);
    }
}
