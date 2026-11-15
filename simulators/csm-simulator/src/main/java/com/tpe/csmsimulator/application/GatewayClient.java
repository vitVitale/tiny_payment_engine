package com.tpe.csmsimulator.application;

/** csm-gateway's inbound endpoint, {@code POST {tpe.sim.gateway-url}/csm/v1/inbound} (specs/17-csm-simulator.md §3.1). */
public interface GatewayClient {

    /**
     * Sends one CSM envelope.
     *
     * @throws RuntimeException if the gateway does not answer {@code 2xx}
     */
    void send(String envelope);
}
