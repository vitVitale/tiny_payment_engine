package com.tpe.paymentapi.infrastructure.config;

import com.tpe.contracts.ContractsModule;
import com.tpe.paymentapi.application.submission.Orchestrator;
import com.tpe.paymentapi.application.submission.PaymentSubmission;
import com.tpe.paymentapi.application.submission.ReferenceChecks;
import com.tpe.paymentapi.application.validation.PaymentValidator;
import com.tpe.paymentapi.infrastructure.orchestrator.HttpOrchestrator;
import com.tpe.paymentapi.infrastructure.vop.HttpReferenceChecks;
import com.tpe.paymentdomain.BankDirectory;
import jakarta.validation.Validator;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The payment initiation path (specs/10-payment-api.md §3.1, §4, §6): the validator, the submission service and its
 * two clients, each with connect timeout 200 ms and its read timeout (specs/00-engineering-baseline.md §8). The
 * clients come from Spring Boot's {@link RestClient.Builder}: the service's JSON and the observation that sends
 * {@code traceparent} on.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PaymentApiConfiguration.OrchestratorProperties.class,
        PaymentApiConfiguration.VopProperties.class})
class PaymentApiConfiguration {

    static final Duration CONNECT_TIMEOUT = Duration.ofMillis(200);

    /** The value objects of the {@code contracts} DTOs as strings (01 §4); Spring Boot registers it with Jackson. */
    @Bean
    ContractsModule contractsModule() {
        return new ContractsModule();
    }

    @Bean
    PaymentValidator paymentValidator(Validator validator, BankDirectory directory) {
        return new PaymentValidator(validator, directory);
    }

    @Bean
    Orchestrator orchestrator(RestClient.Builder builder, OrchestratorProperties orchestrator) {
        return new HttpOrchestrator(client(builder, orchestrator.baseUrl(), orchestrator.timeout()));
    }

    @Bean
    ReferenceChecks referenceChecks(RestClient.Builder builder, VopProperties vop) {
        return new HttpReferenceChecks(client(builder, vop.baseUrl(), vop.checkTimeout()));
    }

    @Bean
    PaymentSubmission paymentSubmission(ReferenceChecks checks, Orchestrator orchestrator) {
        return new PaymentSubmission(checks, orchestrator);
    }

    private static RestClient client(RestClient.Builder builder, URI baseUrl, Duration readTimeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build());
        factory.setReadTimeout(readTimeout);
        return builder.clone().baseUrl(baseUrl.toString()).requestFactory(factory).build();
    }

    /** {@code tpe.clients.orchestrator}. */
    @ConfigurationProperties("tpe.clients.orchestrator")
    record OrchestratorProperties(URI baseUrl, Duration timeout) {}

    /** {@code tpe.clients.vop}. */
    @ConfigurationProperties("tpe.clients.vop")
    record VopProperties(URI baseUrl, Duration checkTimeout) {}
}
