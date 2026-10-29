package com.tpe.observability.sample;

import com.tpe.observability.payment.MdcKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A service that only adds the library, the web and the actuator starter (issue #12 acceptance criteria). */
@SpringBootApplication
public class SampleApplication {

    static final String LOG_MESSAGE = "sample payment handled";

    @RestController
    static class SampleController {

        private static final Logger log = LoggerFactory.getLogger(SampleController.class);

        @GetMapping("/sample")
        String handle() {
            // an inbound adapter sets the payment MDC keys; traceId / spanId come from tracing (issue #13)
            try (var paymentId = MDC.putCloseable(MdcKeys.PAYMENT_ID, "0192f0e4-6b1d-7c3a-9a55-4c1f3b2e9d10");
                    var txId = MDC.putCloseable(MdcKeys.TX_ID, "TPE01JAB3C4D5E6F7G8H9J0K1M2NP");
                    var uetr = MDC.putCloseable(MdcKeys.UETR, "8a562c67-ca16-48ba-b074-65581be6f001");
                    var commandId = MDC.putCloseable(MdcKeys.COMMAND_ID, "0192f0e4-0000-7000-8000-00000000c0de");
                    var eventId = MDC.putCloseable(MdcKeys.EVENT_ID, "0192f0e4-0000-7000-8000-0000000e0e17");
                    var traceId = MDC.putCloseable(MdcKeys.TRACE_ID, "4bf92f3577b34da6a3ce929d0e0e4736");
                    var spanId = MDC.putCloseable(MdcKeys.SPAN_ID, "00f067aa0ba902b7")) {
                log.info(LOG_MESSAGE);
            }
            return "ok";
        }
    }
}
