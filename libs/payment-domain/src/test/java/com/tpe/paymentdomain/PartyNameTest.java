package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.Rejections.assertRejected;
import static com.tpe.paymentdomain.ValidationCode.INVALID_CHARACTERS;
import static com.tpe.paymentdomain.ValidationCode.REQUIRED;
import static com.tpe.paymentdomain.ValidationCode.TOO_LONG;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PartyNameTest {

    @Test
    void keepsTheTrimmedName() {
        assertThat(new PartyName("  Hans Mueller ").value()).isEqualTo("Hans Mueller");
    }

    @Test
    void acceptsSeventyCharactersAfterTrim() {
        String seventy = "A".repeat(70);

        assertThat(new PartyName(" " + seventy + " ").value()).isEqualTo(seventy);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "    "})
    void rejectsEmptyName(String name) {
        assertRejected(() -> new PartyName(name), REQUIRED);
    }

    @Test
    void rejectsMoreThanSeventyCharacters() {
        assertRejected(() -> new PartyName("A".repeat(71)), TOO_LONG);
    }

    @Test
    void rejectsCharactersOutsideTheSepaSet() {
        assertRejected(() -> new PartyName("Hans Müller"), INVALID_CHARACTERS);
    }

    @Test
    void equalByTrimmedValue() {
        assertThat(new PartyName(" Hans Mueller")).isEqualTo(new PartyName("Hans Mueller"));
    }

    @Test
    void neverPrintsTheName() {
        PartyName name = new PartyName("Hans Mueller");

        assertThat(name.toString()).isEqualTo("PartyName[***]");
        assertThat(String.valueOf(name)).doesNotContain("Hans");
    }

    @Test
    void rejectionMessageDoesNotContainTheName() {
        assertThatThrownBy(() -> new PartyName("Hans Müller")).message().doesNotContain("Hans");
        assertThatThrownBy(() -> new PartyName("Hans" + "A".repeat(70))).message().doesNotContain("Hans");
    }
}
