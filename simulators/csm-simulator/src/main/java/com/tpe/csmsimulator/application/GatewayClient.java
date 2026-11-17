package com.tpe.csmsimulator.application;

/** csm-gateway's inbound endpoint, {@code POST {tpe.sim.gateway-url}/csm/v1/inbound} (specs/17-csm-simulator.md §3.1). */
@FunctionalInterface
public interface GatewayClient {

    /**
     * Posts one message as it is.
     *
     * @return the gateway's HTTP status
     * @throws RuntimeException if the gateway gave no answer (connection refused, reset, timed out)
     */
    int post(String message);

    /**
     * Sends one CSM envelope.
     *
     * @throws RuntimeException if the gateway does not answer {@code 2xx}
     */
    default void send(String envelope) {
        int status = post(envelope);
        if (!isSuccess(status)) {
            throw new IllegalStateException(status + " from csm-gateway");
        }
    }

    /** Whether the gateway took the message: a {@code 2xx}. */
    static boolean isSuccess(int status) {
        return status >= 200 && status <= 299;
    }
}
