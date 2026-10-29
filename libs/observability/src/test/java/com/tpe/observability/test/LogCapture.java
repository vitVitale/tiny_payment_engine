package com.tpe.observability.test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.slf4j.LoggerFactory;

/**
 * Captures the log events of one test through a Logback appender on the root logger, and checks them for personal
 * data (specs/26-observability.md §4.4). Register with {@code @RegisterExtension final LogCapture logs = new
 * LogCapture();}. Failures name the level and logger of each offending line, never the canary itself.
 */
public final class LogCapture implements BeforeEachCallback, AfterEachCallback {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @Override
    public void beforeEach(ExtensionContext context) {
        appender.list.clear();
        appender.start();
        root().addAppender(appender);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        root().detachAppender(appender);
        appender.stop();
    }

    /** The events logged so far in this test. */
    public List<ILoggingEvent> events() {
        return List.copyOf(appender.list);
    }

    /** {@link #assertNoPersonalData(PiiCanaries)} with {@link PiiCanaries#DEFAULT}. */
    public void assertNoPersonalData() {
        assertNoPersonalData(PiiCanaries.DEFAULT);
    }

    /**
     * Fails if a canary appears in a line at INFO or above: in its message, MDC, key-value pairs or exception
     * messages (causes and suppressed included).
     */
    public void assertNoPersonalData(PiiCanaries canaries) {
        List<String> offending = new ArrayList<>();
        for (ILoggingEvent event : events()) {
            if (event.getLevel().isGreaterOrEqual(Level.INFO) && texts(event).anyMatch(canaries::foundIn)) {
                offending.add(event.getLevel() + " " + event.getLoggerName());
            }
        }
        if (!offending.isEmpty()) {
            throw new AssertionError(offending.size() + " log lines at INFO or above contain a PII canary "
                    + "(specs/00-engineering-baseline.md §11): " + offending);
        }
    }

    private static Stream<String> texts(ILoggingEvent event) {
        Stream<String> pairs = event.getKeyValuePairs() == null ? Stream.empty()
                : event.getKeyValuePairs().stream().map(pair -> String.valueOf(pair.value));
        return Stream.of(
                        Stream.of(event.getFormattedMessage()),
                        event.getMDCPropertyMap().values().stream(),
                        pairs,
                        throwableMessages(event.getThrowableProxy()))
                .flatMap(stream -> stream);
    }

    private static Stream<String> throwableMessages(IThrowableProxy throwable) {
        if (throwable == null) {
            return Stream.empty();
        }
        Stream<String> suppressed = throwable.getSuppressed() == null ? Stream.empty()
                : Stream.of(throwable.getSuppressed()).flatMap(LogCapture::throwableMessages);
        return Stream.of(Stream.of(throwable.getMessage()), throwableMessages(throwable.getCause()), suppressed)
                .flatMap(stream -> stream);
    }

    private static Logger root() {
        return (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    }
}
