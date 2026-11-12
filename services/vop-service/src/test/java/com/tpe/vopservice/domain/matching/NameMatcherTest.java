package com.tpe.vopservice.domain.matching;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

/**
 * Name scoring and its mapping to a result (specs/13-vop-service.md §6.2): Jaro-Winkler on the normalised names,
 * the max of the token-sorted and the unsorted strings, mapped by {@code match-threshold} and
 * {@code close-match-threshold}.
 */
class NameMatcherTest {

    private final NameNormaliser normaliser = NameNormaliser.withDefaults();
    private final NameMatcher matcher = NameMatcher.withDefaults();

    @ParameterizedTest(name = "[{index}] {0} ← {1}: {2}")
    @CsvFileSource(resources = "/name-matching-cases.csv", numLinesToSkip = 1)
    void VOP_AC_04_theNameMatchingTablePasses(String holder, String input, VerificationResult expected) {
        assertThat(matcher.match(normaliser.normalise(holder), normaliser.normalise(input)))
                .as("score %.4f", matcher.score(normaliser.normalise(holder), normaliser.normalise(input)))
                .isEqualTo(expected);
    }

    @Test
    void wordOrderScoresAsTheTokenSortedStrings() {
        assertThat(matcher.score("anna schmidt", "schmidt anna")).isEqualTo(1.0);
        assertThat(JaroWinkler.similarity("anna schmidt", "schmidt anna")).isLessThan(0.85);
    }

    @Test
    void theUnsortedStringsCountWhenTheyScoreHigher() {
        // sorted, the differing token moves into the middle ("anna berg schmidt" / "anna bergs schmidt", 0.977);
        // unsorted, the strings share all but their last letter (0.989)
        String holder = "anna schmidt berg";
        String input = "anna schmidt bergs";
        double unsorted = JaroWinkler.similarity(holder, input);

        assertThat(unsorted).isGreaterThan(JaroWinkler.similarity("anna berg schmidt", "anna bergs schmidt"));
        assertThat(matcher.score(holder, input)).isEqualTo(unsorted);
    }

    @Test
    void thresholdsAreInclusive() {
        var matcher = new NameMatcher(0.9667, 0.9);
        double score = matcher.score("anna schmidt", "anna schmitt");
        assertThat(score).isCloseTo(0.9667, within(0.0001));

        assertThat(new NameMatcher(score, 0.85).match("anna schmidt", "anna schmitt"))
                .isEqualTo(VerificationResult.MATCH);
        assertThat(new NameMatcher(1.0, score).match("anna schmidt", "anna schmitt"))
                .isEqualTo(VerificationResult.CLOSE_MATCH);
        assertThat(new NameMatcher(1.0, Math.nextUp(score)).match("anna schmidt", "anna schmitt"))
                .isEqualTo(VerificationResult.NO_MATCH);
    }

    @Test
    void anEmptyNameNeverMatches() {
        assertThat(matcher.match("", "")).isEqualTo(VerificationResult.NO_MATCH);
        assertThat(matcher.match("anna schmidt", "")).isEqualTo(VerificationResult.NO_MATCH);
        assertThat(matcher.match("", "anna schmidt")).isEqualTo(VerificationResult.NO_MATCH);
    }

    @Test
    void rejectsThresholdsOutOfOrderOrRange() {
        assertThatIllegalArgumentException().isThrownBy(() -> new NameMatcher(0.85, 0.95));
        assertThatIllegalArgumentException().isThrownBy(() -> new NameMatcher(1.01, 0.85));
        assertThatIllegalArgumentException().isThrownBy(() -> new NameMatcher(0.95, 0.0));
        assertThatIllegalArgumentException().isThrownBy(() -> new NameMatcher(Double.NaN, 0.85));
    }
}
