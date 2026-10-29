package com.tpe.observability.config;

import com.tpe.observability.context.Spans;
import com.tpe.observability.context.TraceContextCodec;
import com.tpe.observability.export.CountingSpanExporter;
import com.tpe.observability.export.DropOnFullSpanProcessor;
import com.tpe.observability.export.DroppedSpans;
import com.tpe.observability.http.ExternalBoundary;
import com.tpe.observability.http.InboundBoundaryPropagator;
import com.tpe.observability.payment.PaymentTelemetry;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SpanExporters;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SpanProcessors;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.server.observation.ServerRequestObservationContext;

/**
 * Tracing of specs/26-observability.md §4.2 and §5 on top of Spring Boot's OpenTelemetry tracing: bounded export
 * that drops and counts instead of blocking, no spans for {@code /actuator/**}, {@link PaymentTelemetry},
 * {@link Spans} and the external boundary in both directions. The OTLP endpoint, the batch settings and the JDBC
 * observation settings are defaults of {@link ObservabilityEnvironmentPostProcessor}. Each part is only active when
 * the service has the library it builds on (Boot's OpenTelemetry tracing, Spring MVC, Boot's RestClient).
 */
@AutoConfiguration(beforeName = "org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure."
        + "OpenTelemetryTracingAutoConfiguration")
@ConditionalOnProperty(name = ObservabilityEnvironmentPostProcessor.ENABLED, matchIfMissing = true)
public class TracingAutoConfiguration {

    static final String EXPORT = "management.opentelemetry.tracing.export.";

    @Bean
    PaymentTelemetry paymentTelemetry(ObjectProvider<Tracer> tracer, ObjectProvider<Clock> clock) {
        return new PaymentTelemetry(tracerOrNoop(tracer), clock.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    TraceContextCodec traceContextCodec(ObjectProvider<Tracer> tracer) {
        return new TraceContextCodec(tracerOrNoop(tracer));
    }

    @Bean
    Spans spans(ObjectProvider<Tracer> tracer, TraceContextCodec codec) {
        return new Spans(tracerOrNoop(tracer), codec);
    }

    /** Replaces Boot's span exporters and processors so the batch processor is bounded and drops are counted. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({SdkTracerProvider.class, SpanExporters.class})
    static class BoundedExport {

        @Bean
        DroppedSpans droppedSpans() {
            return new DroppedSpans();
        }

        @Bean
        CountingExport countingExport(ObjectProvider<SpanExporter> exporters, DroppedSpans dropped) {
            return new CountingExport(new CountingSpanExporter(
                    SpanExporter.composite(exporters.orderedStream().toList()), dropped));
        }

        /** Boot's batch span processor exports through the counting exporter only. */
        @Bean
        SpanExporters spanExporters(CountingExport export) {
            return SpanExporters.of(List.of(export.exporter()));
        }

        /** Boot's batch span processor, behind the drop-on-full gate; other span processors as they are. */
        @Bean
        SpanProcessors spanProcessors(ObjectProvider<SpanProcessor> processors, CountingExport export,
                DroppedSpans dropped, Environment environment) {
            int capacity = environment.getRequiredProperty(EXPORT + "max-queue-size", Integer.class);
            boolean unsampled = environment.getProperty(EXPORT + "include-unsampled", Boolean.class, false);
            return SpanProcessors.of(processors.orderedStream()
                    .map(processor -> processor instanceof BatchSpanProcessor batch
                            ? new DropOnFullSpanProcessor(batch, export.exporter(), capacity, unsampled, dropped)
                            : processor)
                    .toList());
        }

        /** Holds the counting exporter; not itself a {@link SpanExporter} bean, so it does not wrap itself. */
        record CountingExport(CountingSpanExporter exporter) {}
    }

    /** §4.2: no spans (and no {@code http.server.requests} samples) for actuator requests. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({ServerRequestObservationContext.class, HttpServletRequest.class})
    static class ServerObservations {

        @Bean
        ObservationPredicate noActuatorObservations(Environment environment) {
            String basePath = environment.getProperty("management.endpoints.web.base-path", "/actuator");
            return (name, context) -> !(context instanceof ServerRequestObservationContext server
                    && isUnder(server.getCarrier(), basePath));
        }

        private static boolean isUnder(HttpServletRequest request, String basePath) {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            return path.equals(basePath) || path.startsWith(basePath + "/");
        }

        /** §5.3: customer channels' {@code traceparent} becomes a link; the CSM's and counterparties' is ignored. */
        @Bean
        static BeanPostProcessor inboundBoundary(ObjectProvider<Tracer> tracer,
                ObjectProvider<TraceContextCodec> codec, Environment environment) {
            List<String> linkPaths = stringList(environment, "tpe.observability.inbound.link-paths");
            List<String> ignorePaths = stringList(environment, "tpe.observability.inbound.ignore-paths");
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof Propagator propagator && !(bean instanceof InboundBoundaryPropagator)
                            ? new InboundBoundaryPropagator(propagator, tracer::getObject, codec::getObject,
                                    linkPaths, ignorePaths)
                            : bean;
                }
            };
        }
    }

    /** §5.3: no trace headers to external hosts. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RestClientCustomizer.class)
    static class ClientBoundary {

        @Bean
        ExternalBoundary externalBoundary(Environment environment) {
            return new ExternalBoundary(stringList(environment, "tpe.observability.external-hosts"));
        }
    }

    /** Without a tracing implementation, telemetry still works: MDC only, no spans. */
    private static Tracer tracerOrNoop(ObjectProvider<Tracer> tracer) {
        return tracer.getIfAvailable(() -> Tracer.NOOP);
    }

    private static List<String> stringList(Environment environment, String property) {
        return Binder.get(environment).bind(property, Bindable.listOf(String.class)).orElse(List.of());
    }
}
