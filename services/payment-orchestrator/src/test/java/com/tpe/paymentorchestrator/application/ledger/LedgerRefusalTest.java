package com.tpe.paymentorchestrator.application.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.ReasonCode;
import com.tpe.paymentorchestrator.domain.payment.RejectionCause;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** The ledger error mapping of specs/11-payment-orchestrator.md §6.2 and specs/12-ledger-service.md §3.4. */
class LedgerRefusalTest {

    @ParameterizedTest
    @CsvSource({
        "ACCOUNT_NOT_FOUND, AC01, BUSINESS",
        "ACCOUNT_CLOSED, AC04, BUSINESS",
        "ACCOUNT_BLOCKED, AC06, BUSINESS",
        "ACCOUNT_NOT_CUSTOMER, AG01, BUSINESS",
        "INSUFFICIENT_FUNDS, AM04, BUSINESS",
        "CUSTOMER_ID_MISSING, MS03, TECHNICAL",
        "CURRENCY_NOT_SUPPORTED, MS03, TECHNICAL",
        "HOLD_RELEASED, MS03, TECHNICAL",
        "IDEMPOTENCY_CONFLICT, MS03, TECHNICAL",
        "SOMETHING_NEW, MS03, TECHNICAL"
    })
    void eachLedgerCodeMapsToItsReasonAndCause(String code, ReasonCode reason, RejectionCause cause) {
        var refusal = new LedgerRefusal(code);

        assertThat(refusal.reason()).isEqualTo(reason);
        assertThat(refusal.cause()).isEqualTo(cause);
    }
}
