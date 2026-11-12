package com.tpe.vopservice.domain.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Jaro-Winkler similarity, prefix scale 0.1 over at most four characters (Winkler 1990). */
class JaroWinklerTest {

    @ParameterizedTest(name = "{0} / {1} = {2}")
    @CsvSource(delimiter = '|', textBlock = """
            martha       | marhta       | 0.9611
            dwayne       | duane        | 0.8400
            dixon        | dicksonx     | 0.8133
            jellyfish    | smellyfish   | 0.8963
            anna schmidt | anna schmitt | 0.9667
            abc          | xyz          | 0.0
            """)
    void knownValues(String a, String b, double expected) {
        assertThat(JaroWinkler.similarity(a, b)).isCloseTo(expected, within(0.0001));
    }

    @Test
    void isSymmetric() {
        assertThat(JaroWinkler.similarity("dixon", "dicksonx")).isEqualTo(JaroWinkler.similarity("dicksonx", "dixon"));
        assertThat(JaroWinkler.similarity("anna", "anna schmidt"))
                .isEqualTo(JaroWinkler.similarity("anna schmidt", "anna"));
    }

    @Test
    void identicalStringsAreOne() {
        assertThat(JaroWinkler.similarity("anna schmidt", "anna schmidt")).isEqualTo(1.0);
        assertThat(JaroWinkler.similarity("a", "a")).isEqualTo(1.0);
    }

    @Test
    void anEmptyStringIsZero() {
        assertThat(JaroWinkler.similarity("", "anna")).isEqualTo(0.0);
        assertThat(JaroWinkler.similarity("anna", "")).isEqualTo(0.0);
        assertThat(JaroWinkler.similarity("", "")).isEqualTo(0.0);
    }

    @Test
    void comparesCodePointsNotCharsOutsideTheBasicPlane() {
        // 𝔞 (U+1D51E) is two chars; as code points the strings differ in one letter of five
        assertThat(JaroWinkler.similarity("𝔞nnas", "annas")).isEqualTo(JaroWinkler.similarity("xnnas", "annas"));
    }
}
