package com.tpe.observability.sample;

import com.tpe.observability.payment.MdcKeys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * A service that adds the library next to the web, actuator, RestClient, JDBC and OpenTelemetry starters (issues #12,
 * #13 acceptance criteria).
 */
@SpringBootApplication
public class SampleApplication {

    static final String LOG_MESSAGE = "sample payment handled";
    /** A query parameter that must not appear on the JDBC span. */
    static final String SECRET_PARAMETER = "OTTILIE_CANARYWOOD";

    @RestController
    static class SampleController {

        private static final Logger log = LoggerFactory.getLogger(SampleController.class);

        private final JdbcTemplate jdbc;
        private final RestClient.Builder restClient;
        private final Environment environment;

        SampleController(JdbcTemplate jdbc, RestClient.Builder restClient, Environment environment) {
            this.jdbc = jdbc;
            this.restClient = restClient;
            this.environment = environment;
        }

        @GetMapping("/sample")
        String handle() {
            // an inbound adapter sets the payment MDC keys; with tracing, traceId / spanId are set as well
            try (var paymentId = MDC.putCloseable(MdcKeys.PAYMENT_ID, "0192f0e4-6b1d-7c3a-9a55-4c1f3b2e9d10");
                    var txId = MDC.putCloseable(MdcKeys.TX_ID, "TPE01JAB3C4D5E6F7G8H9J0K1M2NP");
                    var uetr = MDC.putCloseable(MdcKeys.UETR, "8a562c67-ca16-48ba-b074-65581be6f001");
                    var commandId = MDC.putCloseable(MdcKeys.COMMAND_ID, "0192f0e4-0000-7000-8000-00000000c0de");
                    var eventId = MDC.putCloseable(MdcKeys.EVENT_ID, "0192f0e4-0000-7000-8000-0000000e0e17")) {
                log.info(LOG_MESSAGE);
            }
            return "ok";
        }

        @GetMapping("/sample/query")
        Integer query() {
            return jdbc.queryForObject("select count(*) from information_schema.tables where table_name = ?",
                    Integer.class, SECRET_PARAMETER);
        }

        /** Recorded only by the exemplar test, so its buckets hold that request's exemplar. */
        @GetMapping("/exemplar")
        String exemplar() {
            return "ok";
        }

        /** A customer-channel path: a caller's traceparent becomes a link. */
        @GetMapping("/public/hello")
        String publicHello() {
            return "hello";
        }

        /** A CSM path: a caller's traceparent is ignored. */
        @GetMapping("/csm/v1/inbound")
        String csmInbound() {
            return "ack";
        }

        /** The traceparent this request arrived with, or {@code none}. */
        @GetMapping("/headers")
        String headers(@RequestHeader(name = "traceparent", required = false) String traceparent) {
            return traceparent == null ? "none" : traceparent;
        }

        /** Calls {@code /headers} of this app through {@code host}. */
        @GetMapping("/call")
        String call(@RequestParam String host) {
            String port = environment.getRequiredProperty("local.server.port");
            return restClient.build().get().uri("http://" + host + ":" + port + "/headers").retrieve()
                    .body(String.class);
        }
    }
}
