package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.Rejections.assertRejected;
import static com.tpe.paymentdomain.ValidationCode.INVALID_REASON_CODE;
import static com.tpe.paymentdomain.ValidationCode.REQUIRED;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ReasonCodeTest {

    @Test
    void hasExactlyTheGlossaryCodesPlusMs03() {
        assertThat(Arrays.stream(ReasonCode.values()).map(ReasonCode::code))
                .containsExactlyInAnyOrder(
                        "AB03", "AB05", "AB06", "AC01", "AC04", "AC06", "AG01", "AM04", "FF01", "FR01", "MS03", "RR04");
    }

    // ISO 20022 ExternalStatusReason1Code definitions
    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
        "AB03 | Settlement aborted due to timeout.",
        "AM04 | Amount of funds available to cover specified message amount is insufficient.",
        "MS03 | Reason has not been specified by agent.",
        "RR04 | Regulatory Reason",
    })
    void carriesItsIsoDescription(String code, String description) {
        assertThat(ReasonCode.valueOf(code).description()).isEqualTo(description);
    }

    @Test
    void everyCodeHasADescription() {
        assertThat(ReasonCode.values()).allSatisfy(code -> assertThat(code.description()).isNotBlank());
    }

    @ParameterizedTest
    @ValueSource(strings = {"AB03", "AB05", "AB06", "AC01", "AC04", "AC06", "AG01", "AM04", "FF01", "FR01", "MS03", "RR04"})
    void parsesKnownCodesToTheConstant(String code) {
        assertThat(ReasonCode.parse(code)).isSameAs(ReasonCode.valueOf(code));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AM05", "BE01", "DUPL", "TM01", "am04"})
    void keepsUnknownCodesAsOther(String code) {
        StatusReason reason = ReasonCode.parse(code);

        assertThat(reason).isEqualTo(new ReasonCode.Other(code));
        assertThat(reason.code()).isEqualTo(code);
    }

    @Test
    void statusReasonIsEitherAKnownCodeOrOther() {
        String described = switch (ReasonCode.parse("AM05")) {
            case ReasonCode known -> "known " + known.code();
            case ReasonCode.Other other -> "other " + other.code();
        };

        assertThat(described).isEqualTo("other AM05");
    }

    @ParameterizedTest
    @ValueSource(strings = {"AM04", "MS03", "RR04"})
    void otherRejectsCodesThisDesignKnowsSoTheyAreOnlyTheConstant(String code) {
        assertRejected(() -> new ReasonCode.Other(code), INVALID_REASON_CODE);
    }

    @Test
    void rejectsAnEmptyCode() {
        assertRejected(() -> ReasonCode.parse(""), REQUIRED);
        assertRejected(() -> ReasonCode.parse(" "), REQUIRED);
    }
}
