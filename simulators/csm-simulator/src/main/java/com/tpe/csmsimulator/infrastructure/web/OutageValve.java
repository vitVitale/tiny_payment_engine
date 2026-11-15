package com.tpe.csmsimulator.infrastructure.web;

import com.tpe.csmsimulator.application.CsmSimulation;
import com.tpe.csmsimulator.application.Outage;
import jakarta.servlet.ServletException;
import java.io.IOException;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.ValveBase;
import org.apache.coyote.ActionCode;

/**
 * The outage modes of the CSM transport (specs/17-csm-simulator.md §3.5, SIM-AC-08) for
 * {@code POST /csm/v1/messages}: {@code DOWN} answers {@code 503}, {@code RESET} closes the connection without a
 * response. Either way the message is discarded before anything reads it. A Tomcat valve, because only the connector
 * can drop a connection without answering.
 */
public final class OutageValve extends ValveBase {

    static final String TRANSPORT_PATH = "/csm/v1/messages";

    private final CsmSimulation simulation;

    public OutageValve(CsmSimulation simulation) {
        super(true);
        this.simulation = simulation;
    }

    @Override
    public void invoke(Request request, Response response) throws IOException, ServletException {
        if (TRANSPORT_PATH.equals(request.getRequestURI())) {
            Outage.Mode mode = simulation.outage().mode();
            if (mode == Outage.Mode.DOWN) {
                response.setStatus(503);
                response.setContentLength(0);
                return;
            }
            if (mode == Outage.Mode.RESET) {
                response.getCoyoteResponse().action(ActionCode.CLOSE_NOW, null);
                return;
            }
        }
        getNext().invoke(request, response);
    }
}
