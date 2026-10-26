package com.tpe.paymentdomain;

import static com.tpe.paymentdomain.Rejections.assertRejected;
import static com.tpe.paymentdomain.ValidationCode.INVALID_CHARACTERS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SepaCharacterSetTest {

    @Test
    void acceptsEveryCharacterOfTheEpcBasicLatinSet() {
        String all = "abcdefghijklmnopqrstuvwxyz ABCDEFGHIJKLMNOPQRSTUVWXYZ 0123456789 /-?:().,'+";

        assertThat(SepaCharacterSet.validate(all)).isEqualTo(all);
    }

    @Test
    void acceptsEmptyText() {
        assertThat(SepaCharacterSet.validate("")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Müller", "Café", "Straße", "Łódź", // no silent transliteration
        "a&b", "a@b", "a_b", "a;b", "a\"b", "a!b", "a#b", "a*b", "a=b", "a%b", "a[b]", "a{b}", "a<b>", "a\\b", "a\b", "a|b", "a~b", "a$b", "€5",
        "line\nbreak", "tab\there", "\u00a0nbsp", "ＡＢＣ", "١٢٣",
    })
    void rejectsAnythingElse(String text) {
        assertRejected(() -> SepaCharacterSet.validate(text), INVALID_CHARACTERS);
    }

    @Test
    void rejectionMessageDoesNotContainTheText() {
        assertThatThrownBy(() -> SepaCharacterSet.validate("Hans Müller"))
                .message()
                .doesNotContain("Hans");
    }
}
