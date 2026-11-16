package com.tpe.csmgateway.infrastructure.config;

import com.tpe.contracts.ContractsModule;
import com.tpe.csmgateway.application.GatewayMetrics;
import com.tpe.csmgateway.application.correlation.StatusReportCorrelation;
import com.tpe.csmgateway.application.journal.MessageJournal;
import com.tpe.csmgateway.application.outgoing.CreditTransferSending;
import com.tpe.csmgateway.application.outgoing.OutgoingTransfers;
import com.tpe.csmgateway.application.transmit.CsmClient;
import com.tpe.csmgateway.application.transmit.Transmitter;
import com.tpe.csmgateway.infrastructure.csm.HttpCsmClient;
import com.tpe.messaging.inbox.InboxGuard;
import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.observability.context.Spans;
import com.tpe.paymentdomain.BankDirectory;
import com.tpe.paymentdomain.UlidGenerator;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** Time, ids, the CSM client and the outgoing flow (specs/15-csm-gateway.md §2, §6, §7). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayConfiguration.CsmProperties.class)
class GatewayConfiguration {

    /** {@code tpe.csm.*} (15 §7). */
    @ConfigurationProperties("tpe.csm")
    record CsmProperties(URI baseUrl, Transmit transmit, Timeouts timeouts) {

        record Transmit(Duration lease) {}

        record Timeouts(Duration connect, Duration read) {}

        CsmProperties {
            Objects.requireNonNull(baseUrl, "tpe.csm.base-url");
            Objects.requireNonNull(transmit, "tpe.csm.transmit");
            Objects.requireNonNull(transmit.lease(), "tpe.csm.transmit.lease");
            Objects.requireNonNull(timeouts, "tpe.csm.timeouts");
            Objects.requireNonNull(timeouts.connect(), "tpe.csm.timeouts.connect");
            Objects.requireNonNull(timeouts.read(), "tpe.csm.timeouts.read");
        }
    }

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    /** The value objects of the {@code contracts} DTOs as strings (01 §4); Spring Boot registers it with Jackson. */
    @Bean
    ContractsModule contractsModule() {
        return new ContractsModule();
    }

    @Bean
    UlidGenerator ulidGenerator(Clock clock) {
        return new UlidGenerator(clock, new SecureRandom());
    }

    @Bean
    CsmClient csmClient(RestClient.Builder builder, CsmProperties csm) {
        return new HttpCsmClient(builder, csm.baseUrl(), csm.timeouts().connect(), csm.timeouts().read());
    }

    /** The transmissions of the {@code afterCommit} fast path, one virtual thread each. */
    @Bean(destroyMethod = "close")
    ExecutorService transmissions() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("csm-transmit-", 0).factory());
    }

    @Bean
    Transmitter transmitter(OutgoingTransfers transfers, MessageJournal journal, CsmClient csm, Clock clock,
            CsmProperties properties, ExecutorService transmissions, Spans spans) {
        return new Transmitter(transfers, journal, csm, clock, properties.transmit().lease(), transmissions, spans);
    }

    @Bean
    CreditTransferSending creditTransferSending(OutgoingTransfers transfers, MessageJournal journal,
            InboxGuard inbox, OutboxWriter outbox, Transmitter transmitter, GatewayMetrics metrics, Spans spans,
            Clock clock, UlidGenerator ulids) {
        return new CreditTransferSending(transfers, journal, inbox, outbox, transmitter, metrics, spans, clock, ulids);
    }

    @Bean
    StatusReportCorrelation statusReportCorrelation(OutgoingTransfers transfers, MessageJournal journal,
            OutboxWriter outbox, GatewayMetrics metrics, Spans spans, Clock clock, BankDirectory directory) {
        return new StatusReportCorrelation(transfers, journal, outbox, metrics, spans, clock, directory.ownBic());
    }
}
