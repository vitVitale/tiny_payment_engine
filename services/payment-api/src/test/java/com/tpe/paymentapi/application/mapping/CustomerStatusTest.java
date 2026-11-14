package com.tpe.paymentapi.application.mapping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tpe.contracts.payment.PaymentState;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The status mapping of specs/10-payment-api.md §3.2 (UC-07 3). */
class CustomerStatusTest {

    @Test
    void everyOutgoingStateHasItsCustomerStatus() {
        Map<PaymentState, CustomerStatus> mapped = new EnumMap<>(PaymentState.class);
        for (PaymentState state : PaymentState.values()) {
            if (state != PaymentState.ACCEPT_REQUESTED && state != PaymentState.ACCEPTED) {
                mapped.put(state, CustomerStatus.of(state));
            }
        }

        assertThat(mapped).isEqualTo(Map.of(
                PaymentState.RECEIVED, CustomerStatus.PDNG,
                PaymentState.RISK_CHECKED, CustomerStatus.PDNG,
                PaymentState.FUNDS_RESERVED, CustomerStatus.PDNG,
                PaymentState.SENT_TO_CSM, CustomerStatus.PDNG,
                PaymentState.INVESTIGATING, CustomerStatus.PDNG,
                PaymentState.SETTLED, CustomerStatus.ACSC,
                PaymentState.REJECTED, CustomerStatus.RJCT));
    }

    /** Only outgoing payments are visible through this API (§3.2). */
    @Test
    void anIncomingOnlyStateHasNoCustomerStatus() {
        assertThatThrownBy(() -> CustomerStatus.of(PaymentState.ACCEPT_REQUESTED))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CustomerStatus.of(PaymentState.ACCEPTED))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
