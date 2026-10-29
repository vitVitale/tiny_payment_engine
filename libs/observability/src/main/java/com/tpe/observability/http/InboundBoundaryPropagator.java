package com.tpe.observability.http;

import com.tpe.observability.context.TraceContextCodec;
import io.micrometer.tracing.Link;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.util.AntPathMatcher;

/**
 * Inbound side of the external boundary (specs/26-observability.md §5.3): a server request on a path of
 * {@code tpe.observability.inbound.link-paths} (customer channels) starts a new trace whose root links to the
 * caller's {@code traceparent}; one on {@code tpe.observability.inbound.ignore-paths} (the CSM, counterparties)
 * starts a new trace and ignores it. Every other request, and every other carrier, continues the caller's trace as
 * usual. Wraps the application's {@link Propagator}, so it applies where the server span is created.
 */
public final class InboundBoundaryPropagator implements Propagator {

    private static final AntPathMatcher PATHS = new AntPathMatcher();

    private final Propagator delegate;
    private final Supplier<Tracer> tracer;
    private final Supplier<TraceContextCodec> codec;
    private final List<String> linkPaths;
    private final List<String> ignorePaths;

    /** {@code tracer} and {@code codec} are looked up on first use: the tracer is built after the propagator. */
    public InboundBoundaryPropagator(Propagator delegate, Supplier<Tracer> tracer, Supplier<TraceContextCodec> codec,
            List<String> linkPaths, List<String> ignorePaths) {
        this.delegate = delegate;
        this.tracer = tracer;
        this.codec = codec;
        this.linkPaths = List.copyOf(linkPaths);
        this.ignorePaths = List.copyOf(ignorePaths);
    }

    @Override
    public List<String> fields() {
        return delegate.fields();
    }

    @Override
    public <C> void inject(TraceContext context, C carrier, Setter<C> setter) {
        delegate.inject(context, carrier, setter);
    }

    @Override
    public <C> Span.Builder extract(C carrier, Getter<C> getter) {
        if (carrier instanceof HttpServletRequest request) {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            if (matches(ignorePaths, path)) {
                return tracer.get().spanBuilder().setNoParent();
            }
            if (matches(linkPaths, path)) {
                Span.Builder root = tracer.get().spanBuilder().setNoParent();
                codec.get().decode(getter.get(carrier, "traceparent"))
                        .ifPresent(foreign -> root.addLink(new Link(foreign)));
                return root;
            }
        }
        return delegate.extract(carrier, getter);
    }

    private static boolean matches(List<String> patterns, String path) {
        return patterns.stream().anyMatch(pattern -> PATHS.match(pattern, path));
    }
}
