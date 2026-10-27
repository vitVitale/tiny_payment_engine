package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.Rejections.assertRejected;
import static com.tpe.paymentdomain.ValidationCode.INVALID_CHARACTERS;
import static com.tpe.paymentdomain.ValidationCode.REQUIRED;
import static com.tpe.paymentdomain.ValidationCode.TOO_LONG;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RemittanceInformationTest {

    @Test
    void acceptsUnstructuredTextUpTo140Characters() {
        String max = "Invoice 2026-0001 ".repeat(7) + "A".repeat(14);

        assertThat(max).hasSize(140);
        assertThat(new RemittanceInformation(max).value()).isEqualTo(max);
        assertThat(new RemittanceInformation("Rent October").value()).isEqualTo("Rent October");
    }

    @Test
    void rejectsEmptyText() {
        assertRejected(() -> new RemittanceInformation(""), REQUIRED);
        assertRejected(() -> new RemittanceInformation("  "), REQUIRED);
    }

    @Test
    void rejectsMoreThan140Characters() {
        assertRejected(() -> new RemittanceInformation("A".repeat(141)), TOO_LONG);
    }

    @Test
    void rejectsCharactersOutsideTheSepaSet() {
        assertRejected(() -> new RemittanceInformation("Miete für Oktober"), INVALID_CHARACTERS);
    }

    @Test
    void neverPrintsTheText() {
        RemittanceInformation remittance = new RemittanceInformation("Rent October Hans Mueller");

        assertThat(remittance.toString()).isEqualTo("RemittanceInformation[***]");
    }

    @Test
    void equalByValue() {
        assertThat(new RemittanceInformation("Rent")).isEqualTo(new RemittanceInformation("Rent"));
    }
}
