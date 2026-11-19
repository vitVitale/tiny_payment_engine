package com.tpe.csmgateway.infrastructure.config;

import com.tpe.contracts.ContractsModule;
import com.tpe.csmgateway.application.GatewayMetrics;
import com.tpe.csmgateway.application.answer.AnswerCoordinator;
import com.tpe.csmgateway.application.correlation.StatusReportCorrelation;
import com.tpe.csmgateway.application.inbound.InboundMessages;
import com.tpe.csmgateway.application.incoming.AnswerTransmissions;
import com.tpe.csmgateway.application.incoming.Answers;
import com.tpe.csmgateway.application.incoming.IncomingEvents;
import com.tpe.csmgateway.application.incoming.CreditTransferReceiving;
import com.tpe.csmgateway.application.incoming.IncomingTransfers;
import com.tpe.csmgateway.application.investigation.PaymentStatusRequesting;
import com.tpe.csmgateway.application.investigation.StatusRequestTransmissions;
import com.tpe.csmgateway.application.investigation.StatusRequests;
import com.tpe.csmgateway.application.journal.MessageJournal;
import com.tpe.csmgateway.application.outgoing.CreditTransferSending;
import com.tpe.csmgateway.application.outgoing.OutgoingEvents;
import com.tpe.csmgateway.application.outgoing.OutgoingTransfers;
import com.tpe.csmgateway.application.outgoing.OutgoingTransmissions;
import com.tpe.csmgateway.application.transmit.CsmClient;
import com.tpe.csmgateway.application.transmit.Transmitter;
import com.tpe.csmgateway.infrastructure.csm.HttpCsmClient;
import com.tpe.csmgateway.infrastructure.transmit.TransmitWorker;
import com.tpe.messaging.inbox.InboxGuard;
import com.tpe.messaging.outbox.OutboxWriter;
import com.tpe.observability.context.Spans;
import com.tpe.paymentdomain.BankDirectory;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.UlidGenerator;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Time, ids, the CSM client, the outgoing and incoming flows and status requests (specs/15-csm-gateway.md §2, §6,
 * §7).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayConfiguration.CsmProperties.class)
class GatewayConfiguration {

    /** {@code tpe.csm.*} (15 §7). */
    @ConfigurationProperties("tpe.csm")
    record CsmProperties(URI baseUrl, String bic, Duration answerDeadlineOffset, Duration answerTransmitWindow,
            Transmit transmit, Timeouts timeouts) {

        record Transmit(Duration lease, Duration pollInterval) {}

        record Timeouts(Duration connect, Duration read) {}

        CsmProperties {
            Objects.requireNonNull(baseUrl, "tpe.csm.base-url");
            Objects.requireNonNull(bic, "tpe.csm.bic");
            Objects.requireNonNull(answerDeadlineOffset, "tpe.csm.answer-deadline-offset");
            Objects.requireNonNull(answerTransmitWindow, "tpe.csm.answer-transmit-window");
            Objects.requireNonNull(transmit, "tpe.csm.transmit");
            Objects.requireNonNull(transmit.lease(), "tpe.csm.transmit.lease");
            Objects.requireNonNull(transmit.pollInterval(), "tpe.csm.transmit.poll-interval");
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

    /** The transmissions, of the {@code afterCommit} fast path and of the worker's polls, one virtual thread each. */
    @Bean(destroyMethod = "close")
    ExecutorService transmissions() {
        return Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("csm-transmit-", 0).factory());
    }

    @Bean
    OutgoingEvents outgoingEvents(OutboxWriter outbox, Spans spans, BankDirectory directory) {
        return new OutgoingEvents(outbox, spans, directory.ownBic());
    }

    @Bean
    OutgoingTransmissions outgoingTransmissions(OutgoingTransfers transfers, MessageJournal journal,
            OutgoingEvents events, GatewayMetrics metrics) {
        return new OutgoingTransmissions(transfers, journal, events, metrics);
    }

    /** Transmits the {@code pacs.008}s. */
    @Bean
    @Qualifier("outgoing")
    Transmitter outgoingTransmitter(OutgoingTransmissions outgoing, CsmClient csm, Clock clock,
            CsmProperties properties, ExecutorService transmissions, Spans spans, GatewayMetrics metrics) {
        return new Transmitter(outgoing, csm, clock, properties.transmit().lease(), transmissions, spans, metrics);
    }

    @Bean
    StatusRequestTransmissions statusRequestTransmissions(StatusRequests requests, OutgoingTransfers transfers,
            IncomingTransfers incoming, MessageJournal journal) {
        return new StatusRequestTransmissions(requests, transfers, incoming, journal);
    }

    @Bean
    IncomingEvents incomingEvents(OutboxWriter outbox, Spans spans) {
        return new IncomingEvents(outbox, spans);
    }

    /** Transmits the {@code pacs.028}s. */
    @Bean
    @Qualifier("statusRequests")
    Transmitter statusRequestTransmitter(StatusRequestTransmissions statusRequests, CsmClient csm, Clock clock,
            CsmProperties properties, ExecutorService transmissions, Spans spans, GatewayMetrics metrics) {
        return new Transmitter(statusRequests, csm, clock, properties.transmit().lease(), transmissions, spans,
                metrics);
    }

    @Bean
    TransmitWorker transmitWorker(List<Transmitter> transmitters, CsmProperties properties) {
        return new TransmitWorker(transmitters, properties.transmit().pollInterval());
    }

    @Bean
    CreditTransferSending creditTransferSending(OutgoingTransfers transfers, MessageJournal journal,
            InboxGuard inbox, OutboxWriter outbox, @Qualifier("outgoing") Transmitter outgoingTransmitter,
            GatewayMetrics metrics,
            Spans spans, Clock clock, UlidGenerator ulids) {
        return new CreditTransferSending(transfers, journal, inbox, outbox, outgoingTransmitter, metrics, spans, clock,
                ulids);
    }

    @Bean
    PaymentStatusRequesting paymentStatusRequesting(OutgoingTransfers transfers, IncomingTransfers incoming,
            StatusRequests requests, MessageJournal journal, InboxGuard inbox, OutgoingEvents events,
            IncomingEvents incomingEvents, @Qualifier("statusRequests") Transmitter statusRequestTransmitter,
            GatewayMetrics metrics, Clock clock, UlidGenerator ulids, BankDirectory directory,
            CsmProperties properties) {
        return new PaymentStatusRequesting(transfers, incoming, requests, journal, inbox, events, incomingEvents,
                statusRequestTransmitter, metrics, clock, ulids, directory.ownBic(), new Bic(properties.bic()));
    }

    @Bean
    StatusReportCorrelation statusReportCorrelation(OutgoingTransfers transfers, IncomingTransfers incoming,
            StatusRequests requests, MessageJournal journal, OutgoingEvents events, IncomingEvents incomingEvents,
            GatewayMetrics metrics, BankDirectory directory) {
        return new StatusReportCorrelation(transfers, incoming, requests, journal, events, incomingEvents, metrics,
                directory.ownBic());
    }

    @Bean
    AnswerTransmissions answerTransmissions(IncomingTransfers transfers, MessageJournal journal,
            CsmProperties properties) {
        return new AnswerTransmissions(transfers, journal, properties.answerTransmitWindow());
    }

    /** Transmits the answers to incoming transfers. */
    @Bean
    @Qualifier("answers")
    Transmitter answerTransmitter(AnswerTransmissions answers, CsmClient csm, Clock clock, CsmProperties properties,
            ExecutorService transmissions, Spans spans, GatewayMetrics metrics) {
        return new Transmitter(answers, csm, clock, properties.transmit().lease(), transmissions, spans, metrics);
    }

    @Bean
    Answers answers(MessageJournal journal, @Qualifier("answers") Transmitter answerTransmitter,
            GatewayMetrics metrics, BankDirectory directory, CsmProperties properties) {
        return new Answers(journal, answerTransmitter, metrics, directory.ownBic(), new Bic(properties.bic()));
    }

    @Bean
    CreditTransferReceiving creditTransferReceiving(IncomingTransfers transfers, MessageJournal journal,
            OutboxWriter outbox, Answers answers, Spans spans, UlidGenerator ulids, BankDirectory directory,
            CsmProperties properties) {
        return new CreditTransferReceiving(transfers, journal, outbox, answers, spans, ulids, directory.ownBic(),
                properties.answerDeadlineOffset());
    }

    @Bean
    AnswerCoordinator answerCoordinator(IncomingTransfers transfers, Answers answers, MessageJournal journal,
            InboxGuard inbox, OutboxWriter outbox, GatewayMetrics metrics, Spans spans, Clock clock,
            UlidGenerator ulids) {
        return new AnswerCoordinator(transfers, answers, journal, inbox, outbox, metrics, spans, clock, ulids);
    }

    /** Startup fails unless a dead gateway's command partitions move on before the answer deadline (15 §4). */
    @Bean
    InitializingBean commandSessionBelowAnswerDeadline(KafkaProperties kafka, CsmProperties properties) {
        return () -> CommandSessionBound.requireBelowAnswerDeadline(kafka.buildConsumerProperties(),
                properties.answerDeadlineOffset());
    }

    @Bean
    InboundMessages inboundMessages(CreditTransferReceiving creditTransfers, StatusReportCorrelation statusReports,
            MessageJournal journal, GatewayMetrics metrics, Clock clock) {
        return new InboundMessages(creditTransfers, statusReports, journal, metrics, clock);
    }
}
