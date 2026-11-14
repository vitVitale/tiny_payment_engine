package com.tpe.systemtests.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.tpe.paymentdomain.Iban;
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

    /** The examples of specs/13-vop-service.md §6.2, to the third decimal. */
    @Test
    void jaroWinklerScoresAsVopServiceDoes() {
        assertThat(TestData.nameScore("Anna Schmidt", "Anna Schmitt")).isCloseTo(0.967, within(0.001));
        assertThat(TestData.nameScore("Anna Schmidt-Berg", "Anna Schmitt")).isCloseTo(0.913, within(0.001));
        assertThat(TestData.nameScore("Max Mustermann", "max  mustermann")).isEqualTo(1.0);
    }

    /** E2E-O-19: a misspelling vop-service answers CLOSE_MATCH, well inside [0.85, 0.95). */
    @Test
    void aCloseMatchOfAGeneratedNameScoresBetweenTheThresholds() {
        IntStream.range(0, 200).mapToObj(i -> TestData.newCustomer().name()).forEach(name -> {
            String misspelt = TestData.closeMatchOf(name);
            assertThat(misspelt).isNotEqualTo(name);
            assertThat(TestData.nameScore(name, misspelt)).isBetween(0.87, 0.93);
            assertThat(TestData.personalData()).as("INV-14 looks for it").contains(misspelt);
        });
    }

    /** E2E-O-18: an IBAN of our bank code that passes the checksum, for which the ledger has no account. */
    @Test
    void anUnknownOwnIbanIsValidAndOfOurBank() {
        String iban = TestData.unknownOwnIban();

        assertThat(new Iban(iban).bankCode()).isEqualTo("10010010");
        assertThat(iban).startsWith("DE").hasSize(22);
        assertThat(iban.substring(12, 14)).isEqualTo("99");
        assertThat(TestData.personalData()).as("INV-14 looks for it").contains(iban);
    }
}
