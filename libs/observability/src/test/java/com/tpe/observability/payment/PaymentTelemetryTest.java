package com.tpe.observability.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.Bic;
import com.tpe.paymentdomain.Iban;
import com.tpe.paymentdomain.PartyName;
import com.tpe.paymentdomain.PaymentId;
import com.tpe.paymentdomain.RemittanceInformation;
import com.tpe.paymentdomain.TxId;
import com.tpe.paymentdomain.Uetr;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.test.simple.SimpleSpan;
import io.micrometer.tracing.test.simple.SimpleTracer;
import java.lang.reflect.Executable;
import java.lang.reflect.Modifier;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class PaymentTelemetryTest {

    private static final Instant NOW = Instant.parse("2026-10-21T09:15:31.250Z");
    private static final PaymentTags TAGS = PaymentTags.of(new TxId("TPE01JAB3C4D5E6F7G8H9J0K1M2NP"),
                    new Bic("TPEBDEFFXXX"))
            .paymentId(new PaymentId(UUID.fromString("0192f0e4-6b1d-7c3a-9a55-4c1f3b2e9d10")))
            .uetr(new Uetr("8a562c67-ca16-48ba-b074-65581be6f001"))
            .direction(PaymentDirection.OUTGOING)
            .onUs(false)
            .acceptedAt(Instant.parse("2026-10-21T09:15:30Z"));

    private final SimpleTracer tracer = new SimpleTracer();
    private final PaymentTelemetry telemetry = new PaymentTelemetry(tracer, Clock.fixed(NOW, ZoneOffset.UTC));

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void setsTheSpanAttributesOfTheCurrentSpan() {
        Span span = tracer.nextSpan().name("payment.saga.action").start();
        try (var inScope = tracer.withSpan(span); var payment = telemetry.apply(TAGS)) {
            assertThat(((SimpleSpan) span).getTags())
                    .containsEntry("tpe.payment.id", "0192f0e4-6b1d-7c3a-9a55-4c1f3b2e9d10")
                    .containsEntry("tpe.tx.id", "TPE01JAB3C4D5E6F7G8H9J0K1M2NP")
                    .containsEntry("tpe.uetr", "8a562c67-ca16-48ba-b074-65581be6f001")
                    .containsEntry("tpe.debtor_agent.bic", "TPEBDEFFXXX")
                    .containsEntry("tpe.direction", "OUTGOING")
                    .containsEntry("tpe.on_us", "false")
                    .containsEntry("tpe.payment.elapsed_ms", "1250");
        } finally {
            span.end();
        }
    }

    @Test
    void setsTheMdcKeysAndRestoresThemOnClose() {
        MDC.put(MdcKeys.TX_ID, "outer");

        try (var payment = telemetry.apply(TAGS)) {
            assertThat(MDC.get(MdcKeys.PAYMENT_ID)).isEqualTo("0192f0e4-6b1d-7c3a-9a55-4c1f3b2e9d10");
            assertThat(MDC.get(MdcKeys.TX_ID)).isEqualTo("TPE01JAB3C4D5E6F7G8H9J0K1M2NP");
            assertThat(MDC.get(MdcKeys.UETR)).isEqualTo("8a562c67-ca16-48ba-b074-65581be6f001");
        }

        assertThat(MDC.get(MdcKeys.TX_ID)).isEqualTo("outer");
        assertThat(MDC.get(MdcKeys.PAYMENT_ID)).isNull();
    }

    @Test
    void worksWithoutACurrentSpan() {
        try (var payment = telemetry.apply(PaymentTags.of(TAGS.txId(), TAGS.debtorAgentBic()))) {
            assertThat(MDC.get(MdcKeys.TX_ID)).isEqualTo("TPE01JAB3C4D5E6F7G8H9J0K1M2NP");
            assertThat(MDC.get(MdcKeys.PAYMENT_ID)).isNull();
        }
    }

    @Test
    void apiAcceptsNoPersonalDataOrFreeText() {
        // 26 §4.4 (1): identifiers only; no Iban, name, remittance text or String to smuggle them in
        Stream<Executable> api = Stream.of(PaymentTelemetry.class, PaymentTags.class)
                .flatMap(type -> Stream.concat(Arrays.stream(type.getDeclaredMethods()),
                        Arrays.stream(type.getDeclaredConstructors())))
                .filter(executable -> Modifier.isPublic(executable.getModifiers()))
                .filter(executable -> !executable.getName().equals("equals")); // the record's own

        assertThat(api.flatMap(executable -> Arrays.stream(executable.getParameterTypes())))
                .isNotEmpty()
                .doesNotContain(Iban.class, PartyName.class, RemittanceInformation.class, String.class,
                        CharSequence.class, Object.class);
    }
}
