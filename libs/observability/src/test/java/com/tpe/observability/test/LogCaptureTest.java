package com.tpe.observability.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.paymentdomain.Iban;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

class LogCaptureTest {

    private static final Logger log = LoggerFactory.getLogger(LogCaptureTest.class);
    private static final String NAME = PiiCanaries.DEFAULT.names().iterator().next();
    private static final Iban IBAN = PiiCanaries.DEFAULT.ibans().iterator().next();

    @RegisterExtension
    final LogCapture logs = new LogCapture();

    @Test
    void cleanLinesPass() {
        log.info("payment {} accepted", "0192f0e4-6b1d-7c3a-9a55-4c1f3b2e9d10");
        log.info("debtor iban {}", IBAN); // Iban.toString() is masked

        assertThat(logs.events()).hasSize(2);
        assertThatCode(logs::assertNoPersonalData).doesNotThrowAnyException();
    }

    @Test
    void canaryNameAtInfoFails() {
        log.info("creditor {} unknown", NAME);

        assertThatThrownBy(logs::assertNoPersonalData)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("INFO")
                .hasMessageContaining(LogCaptureTest.class.getName());
    }

    @Test
    void canaryIbanAtWarnFailsAlsoWhenGroupedInFours() {
        log.warn("account {} blocked", PiiCanaries.grouped(IBAN.value()));

        assertThatThrownBy(logs::assertNoPersonalData).isInstanceOf(AssertionError.class);
    }

    @Test
    void canaryInMdcOrExceptionMessageFails() {
        MDC.put("payee", NAME);
        try {
            log.info("verification done");
        } finally {
            MDC.remove("payee");
        }
        log.error("parse failed", new IllegalArgumentException("bad IBAN " + IBAN.value()));

        assertThatThrownBy(logs::assertNoPersonalData)
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("2 log lines");
    }

    @Test
    void failureDoesNotQuoteTheCanary() {
        log.info("creditor {} unknown", NAME);

        assertThatThrownBy(logs::assertNoPersonalData).message().doesNotContain(NAME);
    }

    @Test
    void debugIsNotChecked() {
        log.debug("creditor {} unknown", NAME);

        assertThatCode(logs::assertNoPersonalData).doesNotThrowAnyException();
    }
}
