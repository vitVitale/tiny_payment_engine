package com.tpe.paymentapi.infrastructure.http;

import com.tpe.paymentapi.application.submission.Failure;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.util.Optional;
import java.util.Set;
import org.springframework.web.client.ResourceAccessException;

/** How the clients tell the {@link Failure}s apart (specs/00-engineering-baseline.md §8). */
public final class HttpFailures {

    private static final Set<Integer> GATEWAY = Set.of(502, 503, 504);

    private HttpFailures() {}

    /** A connect failure or a connect timeout: the request was not sent. Anything else timed out reading. */
    public static Failure of(ResourceAccessException e) {
        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException
                    || cause instanceof UnresolvedAddressException) {
                return Failure.CONNECT;
            }
        }
        return Failure.TIMEOUT;
    }

    /** A {@code 5xx} as a failure; empty for any other status. */
    public static Optional<Failure> of(int status) {
        if (GATEWAY.contains(status)) {
            return Optional.of(Failure.GATEWAY);
        }
        return status >= 500 && status <= 599 ? Optional.of(Failure.SERVER_ERROR) : Optional.empty();
    }
}
