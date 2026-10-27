package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.Rejections.assertRejected;
import static com.tpe.paymentdomain.ValidationCode.INVALID_CHARACTERS;
import static com.tpe.paymentdomain.ValidationCode.REQUIRED;
import static com.tpe.paymentdomain.ValidationCode.TOO_LONG;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** TxId, EndToEndId and MsgId are ISO Max35Text in the SEPA character set. */
class Max35TextIdsTest {

    static Stream<Arguments> types() {
        return Stream.of(
                Arguments.of("TxId", (Function<String, Object>) TxId::new, (Function<Object, String>) id -> ((TxId) id).value()),
                Arguments.of("EndToEndId", (Function<String, Object>) EndToEndId::new, (Function<Object, String>) id -> ((EndToEndId) id).value()),
                Arguments.of("MsgId", (Function<String, Object>) MsgId::new, (Function<Object, String>) id -> ((MsgId) id).value()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    void acceptsOneToThirtyFiveSepaCharacters(String type, Function<String, Object> create, Function<Object, String> value) {
        String max = "A".repeat(35);

        assertThat(value.apply(create.apply("X"))).isEqualTo("X");
        assertThat(value.apply(create.apply(max))).isEqualTo(max);
        assertThat(value.apply(create.apply("Ref-2026/10:(a).b,c'd+e?"))).isEqualTo("Ref-2026/10:(a).b,c'd+e?");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    void rejectsEmptyOrBlank(String type, Function<String, Object> create, Function<Object, String> value) {
        assertRejected(() -> create.apply(""), REQUIRED);
        assertRejected(() -> create.apply("   "), REQUIRED);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    void rejectsMoreThanThirtyFiveCharacters(String type, Function<String, Object> create, Function<Object, String> value) {
        assertRejected(() -> create.apply("A".repeat(36)), TOO_LONG);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    void rejectsCharactersOutsideTheSepaSet(String type, Function<String, Object> create, Function<Object, String> value) {
        assertRejected(() -> create.apply("Ref_1"), INVALID_CHARACTERS);
        assertRejected(() -> create.apply("Müller"), INVALID_CHARACTERS);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("types")
    void equalByValue(String type, Function<String, Object> create, Function<Object, String> value) {
        assertThat(create.apply("ABC")).isEqualTo(create.apply("ABC")).isNotEqualTo(create.apply("ABD"));
    }

    @Test
    void endToEndIdAcceptsNotProvided() {
        assertThat(EndToEndId.NOT_PROVIDED.value()).isEqualTo("NOTPROVIDED");
        assertThat(new EndToEndId("NOTPROVIDED")).isEqualTo(EndToEndId.NOT_PROVIDED);
        assertThat(EndToEndId.NOT_PROVIDED.isProvided()).isFalse();
        assertThat(new EndToEndId("INV-2026-0001").isProvided()).isTrue();
    }
}
