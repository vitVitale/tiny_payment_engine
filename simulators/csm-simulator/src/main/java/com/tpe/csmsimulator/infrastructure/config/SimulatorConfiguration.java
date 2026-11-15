package com.tpe.csmsimulator.infrastructure.config;

import com.tpe.csmsimulator.application.CsmSimulation;
import com.tpe.csmsimulator.application.GatewayClient;
import com.tpe.csmsimulator.domain.scheme.OutgoingScheme;
import com.tpe.csmsimulator.infrastructure.gateway.HttpGatewayClient;
import com.tpe.csmsimulator.infrastructure.scheduling.TimelineWorker;
import com.tpe.csmsimulator.infrastructure.web.OutageValve;
import com.tpe.iso20022model.EnvelopeCodec;
import com.tpe.iso20022model.Pacs002Builder;
import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.UlidGenerator;
import java.time.Clock;
import java.util.Random;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.tomcat.servlet.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** The simulated CSM and what drives it (specs/17-csm-simulator.md §2, §4). */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SimulatorProperties.class)
class SimulatorConfiguration {

    /** The simulator's own clock (specs/00-engineering-baseline.md §6); component tests replace it. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    GatewayClient gatewayClient(RestClient.Builder builder, SimulatorProperties properties) {
        return new HttpGatewayClient(builder, properties.gatewayUrl());
    }

    @Bean
    CsmSimulation csmSimulation(GatewayClient gateway, Clock clock, SimulatorProperties properties) {
        // a test tool: the message ids need not be unpredictable
        return new CsmSimulation(new EnvelopeCodec(),
                new OutgoingScheme(properties.beneficiaryDelay(), properties.timeoutDeadline()),
                new Pacs002Builder(), gateway, clock, new UlidGenerator(clock, new Random()),
                new Bic(properties.ownBic()));
    }

    @Bean
    TimelineWorker timelineWorker(CsmSimulation simulation, SimulatorProperties properties) {
        return new TimelineWorker(simulation, properties.tick());
    }

    /** {@code DOWN} and {@code RESET} act before Spring MVC sees the request (SIM-AC-08). */
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> outageValve(CsmSimulation simulation) {
        return factory -> factory.addContextValves(new OutageValve(simulation));
    }
}
