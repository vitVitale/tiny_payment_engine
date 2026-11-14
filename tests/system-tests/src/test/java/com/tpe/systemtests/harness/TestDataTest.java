package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;

import com.tpe.paymentdomain.PartyName;
import java.util.HashSet;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TestDataTest {

    @Test
    void everyCustomerIsNewAndValidForTheLedger() {
        var customers = IntStream.range(0, 1_000).mapToObj(i -> TestData.newCustomer()).toList();

        assertThat(new HashSet<>(customers.stream().map(TestData.Customer::customerId).toList())).hasSize(1_000);
        assertThat(new HashSet<>(customers.stream().map(TestData.Customer::name).toList())).hasSize(1_000);
        // 12 §3.2: 1–50 chars [A-Za-z0-9_-]
        assertThat(customers).allSatisfy(customer -> {
            assertThat(customer.customerId()).matches("[A-Za-z0-9_-]{1,50}");
            assertThat(new PartyName(customer.name()).value()).isEqualTo(customer.name());
        });
    }

    /** INV-14 scans the logs for every name and IBAN the tests used. */
    @Test
    void theNamesGivenOutAndTheIbansRememberedArePersonalDataToScanFor() {
        TestData.Customer customer = TestData.newCustomer();
        TestData.rememberIban("DE36100100100000000099");

        assertThat(TestData.personalData()).contains(customer.name(), "DE36100100100000000099")
                .doesNotContain(customer.customerId());
    }
}
